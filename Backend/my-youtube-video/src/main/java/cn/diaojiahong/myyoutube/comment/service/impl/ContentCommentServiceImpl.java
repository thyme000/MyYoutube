package cn.diaojiahong.myyoutube.comment.service.impl;

import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.comment.cache.CommentListCache;
import cn.diaojiahong.myyoutube.comment.cursor.CommentCursor;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentDTO;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentListQuery;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentReplyQuery;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentTab;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentPageVO;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentVO;
import cn.diaojiahong.myyoutube.comment.mapper.ContentCommentMapper;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.mq.message.CommentMessage;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 视频评论表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
@Service
@RequiredArgsConstructor
public class ContentCommentServiceImpl extends ServiceImpl<ContentCommentMapper, ContentComment> implements IContentCommentService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int STORAGE_EXPIRE = 60 * 20;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    /** 名单里的失效条目(取回时已被删/改状态)会让当页变短, 每次多取一点并循环补齐 */
    private static final int OVERFETCH = 10;
    private static final int MAX_CHUNKS = 3;

    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final IContentService contentService;
    private final cn.diaojiahong.myyoutube.content.service.ContentAccessService access;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CommentListCache commentListCache;

    @Override
    @ObservedBusiness(BusinessOperation.COMMENT)
    public void publishComment(CommentDTO commentDTO) {
        Long parentId = commentDTO.getParentId() == null ? 0L : commentDTO.getParentId();
        Long userId = getUserId();
        Content content = access.readable(commentDTO.getContentId(), userId);
        access.requireCommentable(content, userId);
        // 父评论校验放事务外, 只在回复时查
        ContentComment parent = null;
        if (parentId != 0) {
            ContentComment p = getById(parentId);
            if (p == null || !CommentStatusEnum.VISIBLE.getCode().equals(p.getStatus())) {

                throw new IllegalArgumentException("父评论不存在或不可回复");
            }
            if (!Objects.equals(p.getContentId(), commentDTO.getContentId())) {

                throw new IllegalArgumentException("父评论不属于该内容");
            }
            parent = p;
        }
        final ContentComment parentRef = parent;
        ContentComment comment = new ContentComment()
                .setUserId(userId)
                .setContentId(commentDTO.getContentId())
                .setContent(commentDTO.getContent())
                .setLikeCount(0)
                .setReplyCount(0)
                .setStatus(CommentStatusEnum.VISIBLE.getCode());
        if (parentRef != null) {
            comment.setReplyUserId(parentRef.getUserId());
        }
        // save() 后 DB 生成的 create_time 不回填内存对象, 显式赋值: 游标与榜单排序都靠它
        comment.setCreateTime(LocalDateTime.now());
        // 事务只圈 DB 写, 避免包住 MQ
        transactionTemplate.executeWithoutResult(tx -> {
            access.requireCommentable(access.locked(content.getId()), userId);
            save(comment);
            // path 要用自增 id, 只能先 save 再回填
            comment.setPath(parentRef == null ? String.valueOf(comment.getId()) : parentRef.getPath() + "." + comment.getId());
            comment.setLevel(parentRef == null ? 1 : parentRef.getLevel() + 1);
            updateById(comment);
            if (parentRef != null) {
                lambdaUpdate().eq(ContentComment::getId, rootIdOf(parentRef))
                        .setIncrBy(ContentComment::getReplyCount, 1).update();
            }
            contentService.lambdaUpdate().eq(Content::getId, commentDTO.getContentId())
                    .setIncrBy(Content::getCommentCount, 1).update();
        });

        // 事务已提交, MQ 失败不回滚评论
        CommentMessage message = null;
        if (parent == null) {
            // 接受方是内容作者, 自己评自己不通知
            if (!userId.equals(content.getAuthorId())) {

                message = CommentMessage.builder()
                       .content(commentDTO.getContent()).contentId(content.getId())
                       .senderId(userId).receiverId(content.getAuthorId()).isContentComment(true)
                       .commentId(comment.getId()).build();
            }
        } else {
            if (!userId.equals(parent.getUserId())) {

                message = CommentMessage.builder()
                        .content(commentDTO.getContent()).contentId(content.getId())
                        .senderId(userId).receiverId(parent.getUserId()).isContentComment(false)
                        .commentId(comment.getId()).build();
            }
        }
        if (message != null) {

            rocketMQTemplate.syncSend(MqConstants.TOPIC_COMMENT_ACTION + ":" + MqConstants.TAG_PUBLISH, message);
        }
    }

    @Override
    @ObservedBusiness(BusinessOperation.DELETE_COMMENT)
    public void deleteComment(Long commentId) {
        ContentComment comment = getById(commentId);
        if (comment == null) {

            throw new IllegalArgumentException("评论不存在");
        }
        Long userId = getUserId();
        if (!comment.getUserId().equals(userId)) {

            throw new IllegalArgumentException("用户没有权限删除评论");
        }
        // 删楼时连回复一起摘, 先收齐受影响 id 供计数
        List<Long> affectedIds = new ArrayList<>();
        affectedIds.add(commentId);
        boolean isRoot = comment.getLevel() != null && comment.getLevel() == 1;
        if (isRoot) {
            lambdaQuery()
                    .likeRight(ContentComment::getPath, commentId + ".")
                    .ne(ContentComment::getStatus, CommentStatusEnum.DELETED.getCode())
                    .select(ContentComment::getId)
                    .list()
                    .forEach(c -> affectedIds.add(c.getId()));
        }
        long removed = affectedIds.size();
        transactionTemplate.executeWithoutResult(tx -> {
            comment.setStatus(CommentStatusEnum.DELETED.getCode());
            updateById(comment);
            if (isRoot) {
                baseMapper.update(null, new LambdaUpdateWrapper<ContentComment>()
                        .likeRight(ContentComment::getPath, commentId + ".")
                        .ne(ContentComment::getStatus, CommentStatusEnum.DELETED.getCode())
                        .set(ContentComment::getStatus, CommentStatusEnum.DELETED.getCode()));
            } else {
                lambdaUpdate().eq(ContentComment::getId, rootIdOf(comment))
                        .gt(ContentComment::getReplyCount, 0)
                        .setIncrBy(ContentComment::getReplyCount, -1).update();
            }
            // 更新内容的评论数, 带护底避免减成负数
            contentService.lambdaUpdate().eq(Content::getId, comment.getContentId())
                    .ge(Content::getCommentCount, removed)
                    .setIncrBy(Content::getCommentCount, -removed).update();
        });
    }

    @Override
    public CommentPageVO getRootComments(CommentListQuery query) {
        Long contentId = query.getContentId();
        Content content = access.readable(contentId, getUserId());
        if (content == null) {

            throw new IllegalArgumentException("内容不存在");
        }
        CommentTab tab = query.getTab() == null || query.getTab().isBlank()
                ? CommentTab.HOT : CommentTab.of(query.getTab());
        int limit = clampPageSize(query.getPageSize());
        CommentCursor cursor = CommentCursor.decode(query.getCursor());

        List<CommentCursor> list = commentListCache.get(contentId, tab);
        if (list == null) {
            commentListCache.build(contentId, tab, content.getCommentCount());
            list = commentListCache.get(contentId, tab);
        }
        // 名单的排序值是建榜时的快照, 必须拿它当游标; DB 路径的排序值取自当前行, 两界各自自洽
        Map<Long, CommentCursor> sourceCursors = new LinkedHashMap<>();
        List<ContentComment> rows = list != null
                ? pageFromList(contentId, tab, list, cursor, limit + 1, sourceCursors)
                : pageFromDb(contentId, tab, cursor, limit + 1, sourceCursors);

        boolean hasMore = rows.size() > limit;
        List<ContentComment> page = hasMore ? rows.subList(0, limit) : rows;
        CommentPageVO result = new CommentPageVO();
        result.setList(assemble(page));
        result.setHasMore(hasMore);
        if (hasMore && !page.isEmpty()) {
            result.setNextCursor(sourceCursors.get(page.get(page.size() - 1).getId()).encode());
        }
        return result;
    }

    @Override
    public CommentPageVO getReplies(CommentReplyQuery query) {
        Long rootId = query.getRootId();
        ContentComment root = getById(rootId);
        if (root == null) {

            throw new IllegalArgumentException("评论不存在");
        }
        access.readable(root.getContentId(), getUserId());
        int limit = clampPageSize(query.getPageSize());
        CommentCursor cursor = CommentCursor.decode(query.getCursor());
        LambdaQueryWrapper<ContentComment> w = new LambdaQueryWrapper<ContentComment>()
                .likeRight(ContentComment::getPath, rootId + ".")
                .in(ContentComment::getStatus, CommentStatusEnum.VISIBLE.getCode(), CommentStatusEnum.BLOCKED.getCode());
        if (cursor != null) {
            long ts = cursor.getScore();
            w.and(x -> x.gt(ContentComment::getCreateTime, toLocalDateTime(ts))
                    .or(o -> o.eq(ContentComment::getCreateTime, toLocalDateTime(ts))
                            .gt(ContentComment::getId, cursor.getId())));
        }
        w.orderByAsc(ContentComment::getCreateTime).orderByAsc(ContentComment::getId)
                .last("LIMIT " + (limit + 1));
        List<ContentComment> records = list(w);

        boolean hasMore = records.size() > limit;
        List<ContentComment> page = hasMore ? records.subList(0, limit) : records;
        CommentPageVO result = new CommentPageVO();
        result.setList(assemble(page));
        result.setHasMore(hasMore);
        if (hasMore && !page.isEmpty()) {
            ContentComment last = page.get(page.size() - 1);
            result.setNextCursor(CommentCursor.of(epochSecond(last.getCreateTime()), last.getId()).encode());
        }
        return result;
    }

    /**
     * 按名单取页: 名单条目可能已失效(期间被删或改状态), 故按 id 批查时带上 status 过滤,
     * 当页取不满就继续取下一块; latest 的名单取尽后还能接 DB(排序值不可变, 两界游标一致)。
     */
    private List<ContentComment> pageFromList(Long contentId, CommentTab tab, List<CommentCursor> list,
                                             CommentCursor cursor, int need, Map<Long, CommentCursor> sourceCursors) {
        List<ContentComment> rows = new ArrayList<>(need);
        int idx = startIndex(list, cursor);
        int chunks = 0;
        while (rows.size() < need && idx < list.size() && chunks < MAX_CHUNKS) {
            int take = Math.min(need - rows.size() + OVERFETCH, list.size() - idx);
            List<CommentCursor> slice = list.subList(idx, idx + take);
            appendSlice(rows, slice, sourceCursors);
            idx += take;
            chunks++;
        }
        // hot 的排序值(点赞数)会变, 跨名单与 DB 混用同一游标会错位, 故 hot 到此为止
        if (rows.size() < need && idx >= list.size() && tab == CommentTab.LATEST) {
            CommentCursor from = rows.isEmpty() ? cursor : sourceCursors.get(rows.get(rows.size() - 1).getId());
            appendRows(rows, pageFromDb(contentId, tab, from, need - rows.size(), sourceCursors), tab, sourceCursors);
        }
        return rows;
    }

    private void appendRows(List<ContentComment> rows, List<ContentComment> chunk, CommentTab tab,
                            Map<Long, CommentCursor> sourceCursors) {
        chunk.forEach(row -> {
            rows.add(row);
            sourceCursors.put(row.getId(), cursorOf(tab, row));
        });
    }

    private void appendSlice(List<ContentComment> rows, List<CommentCursor> slice,
                             Map<Long, CommentCursor> sourceCursors) {
        if (slice.isEmpty()) {
            return;
        }
        List<Long> ids = slice.stream().map(CommentCursor::getId).toList();
        Map<Long, ContentComment> found = lambdaQuery()
                .in(ContentComment::getId, ids)
                .in(ContentComment::getStatus, CommentStatusEnum.VISIBLE.getCode(), CommentStatusEnum.BLOCKED.getCode())
                .list().stream()
                .collect(Collectors.toMap(ContentComment::getId, Function.identity()));
        for (CommentCursor c : slice) {
            ContentComment row = found.get(c.getId());
            if (row != null) {
                rows.add(row);
                sourceCursors.put(row.getId(), c);
            }
        }
    }

    /** DB keyset 取页, 用当前行的排序值当游标 */
    private List<ContentComment> pageFromDb(Long contentId, CommentTab tab, CommentCursor cursor, int need,
                                           Map<Long, CommentCursor> sourceCursors) {
        List<ContentComment> rows = new ArrayList<>(need);
        CommentCursor from = cursor;
        int chunks = 0;
        while (rows.size() < need && chunks < MAX_CHUNKS) {
            int want = need - rows.size();
            List<ContentComment> chunk = queryRootsFromDb(contentId, tab, from, want);
            appendRows(rows, chunk, tab, sourceCursors);
            if (chunk.size() < want) {
                break;
            }
            from = cursorOf(tab, chunk.get(chunk.size() - 1));
            chunks++;
        }
        return rows;
    }

    private List<ContentComment> queryRootsFromDb(Long contentId, CommentTab tab, CommentCursor cursor, int limit) {
        LambdaQueryWrapper<ContentComment> w = new LambdaQueryWrapper<ContentComment>()
                .eq(ContentComment::getContentId, contentId)
                .eq(ContentComment::getLevel, 1)
                .in(ContentComment::getStatus, CommentStatusEnum.VISIBLE.getCode(), CommentStatusEnum.BLOCKED.getCode());
        if (cursor != null) {
            if (tab == CommentTab.LATEST) {
                long ts = cursor.getScore();
                w.and(x -> x.lt(ContentComment::getCreateTime, toLocalDateTime(ts))
                        .or(o -> o.eq(ContentComment::getCreateTime, toLocalDateTime(ts))
                                .lt(ContentComment::getId, cursor.getId())));
            } else {
                w.and(x -> x.lt(ContentComment::getLikeCount, cursor.getScore())
                        .or(o -> o.eq(ContentComment::getLikeCount, (int) cursor.getScore())
                                .lt(ContentComment::getId, cursor.getId())));
            }
        }
        if (tab == CommentTab.LATEST) {
            w.orderByDesc(ContentComment::getCreateTime).orderByDesc(ContentComment::getId);
        } else {
            w.orderByDesc(ContentComment::getLikeCount).orderByDesc(ContentComment::getId);
        }
        w.last("LIMIT " + limit);
        return list(w);
    }

    /** 名单按榜序降序, 从头找到第一个排在游标之后的元素 */
    private static int startIndex(List<CommentCursor> list, CommentCursor cursor) {
        if (cursor == null) {
            return 0;
        }
        for (int i = 0; i < list.size(); i++) {
            CommentCursor e = list.get(i);
            if (e.getScore() < cursor.getScore()
                    || (e.getScore() == cursor.getScore() && e.getId() < cursor.getId())) {
                return i;
            }
        }
        return list.size();
    }

    private CommentCursor cursorOf(CommentTab tab, ContentComment row) {
        return tab == CommentTab.LATEST
                ? CommentCursor.of(epochSecond(row.getCreateTime()), row.getId())
                : CommentCursor.of(row.getLikeCount() == null ? 0 : row.getLikeCount(), row.getId());
    }

    private List<CommentVO> assemble(List<ContentComment> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<CommentVO> voList = records.stream().map(record -> {
            CommentVO vo = BeanUtil.toBean(record, CommentVO.class);
            vo.setContentId(record.getContentId());
            vo.setCreateTime(record.getCreateTime() == null ? null : TIME_FORMATTER.format(record.getCreateTime()));
            if (CommentStatusEnum.BLOCKED.getCode().equals(record.getStatus())) {
                vo.setContent("[评论违规,已被处理]");
            }
            return vo;
        }).toList();

        // 评论者与回复者合并成一次批量取用户
        Set<Long> allUserIds = new LinkedHashSet<>();
        voList.forEach(vo -> {
            allUserIds.add(vo.getUserId());
            if (vo.getReplyUserId() != null) {
                allUserIds.add(vo.getReplyUserId());
            }
        });
        List<UserSummary> users = userPort.listByIds(allUserIds);
        Map<Long, String> nicknames = UserSummaries.toNicknameMap(users);
        Map<Long, String> avatarUrls = UserSummaries.toAvatarMap(users);
        voList.forEach(vo -> {
            vo.setNickname(nicknames.get(vo.getUserId()));
            vo.setAvatarUrl(storageUtil.signGetUrl(avatarUrls.get(vo.getUserId()), STORAGE_EXPIRE));
            vo.setReplyNickname(nicknames.get(vo.getReplyUserId()));
        });

        // isLike 是用户维度数据, 不进缓存, 每请求实时补
        Long userId = getUserId();
        String key = RedisConstants.USER_LIKE_COMMENT + userId;
        StringRedisSerializer strSer = new StringRedisSerializer();
        byte[] rawKey = strSer.serialize(key);
        List<Object> scoreResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (CommentVO v : voList) {

                byte[] member = strSer.serialize(v.getId().toString());
                connection.zScore(rawKey, member);
            }
            return null;
        });
        for (int i = 0; i < voList.size(); i++) {

            Double score = (Double) scoreResults.get(i);
            voList.get(i).setIsLike(score != null);
        }
        return voList;
    }

    private static Long rootIdOf(ContentComment comment) {
        String path = comment.getPath();
        int idx = path.indexOf('.');
        return Long.parseLong(idx < 0 ? path : path.substring(0, idx));
    }

    private static long epochSecond(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private static LocalDateTime toLocalDateTime(long epochSecond) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSecond), ZoneId.systemDefault());
    }

    private static int clampPageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {

            throw new IllegalArgumentException("用户未登录");
        }
        return Long.valueOf(userId);
    }
}

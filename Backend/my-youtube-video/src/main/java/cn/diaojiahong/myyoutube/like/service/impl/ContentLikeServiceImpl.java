package cn.diaojiahong.myyoutube.like.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.follow.cache.FollowSnapshotCache;
import cn.diaojiahong.myyoutube.like.domain.constants.ContentLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.ContentCommentLike;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.domain.vo.LikedContentVO;
import cn.diaojiahong.myyoutube.like.mapper.ContentCommentLikeMapper;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.like.service.IContentLikeService;
import cn.diaojiahong.myyoutube.mq.message.LikeCommentMessage;
import cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag;
import cn.diaojiahong.myyoutube.recommend.mapper.ContentTagMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import cn.diaojiahong.myyoutube.mq.message.CommentMessage;

/**
 * <p>
 * 内容点赞表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentLikeServiceImpl extends ServiceImpl<ContentLikeMapper, ContentLike> implements IContentLikeService {

    public static final long LIKED_COMMENT_EXPIRE_DAYS = 10L;

    public static String likedCommentKey(Long userId) {
        return "user:like:comment:" + userId;
    }

    private final ContentAccessService access;
    private final ContentSummaryPort contentSummaryPort;
    private final StringRedisTemplate redisTemplate;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final ContentLikeMapper contentLikeMapper;
    private final ContentCommentLikeMapper contentCommentLikeMapper;
    private final IContentCommentService contentCommentService;
    private final RocketMQTemplate rocketMQTemplate;
    private final FollowSnapshotCache followSnapshot;
    private final CounterBuffer counterBuffer;
    private final ContentTagMapper contentTagMapper;
    private final IUserTagPreferenceService tagPreferences;
    private final UserBlockPort blocks;

    private static final int STORAGE_EXPIRE = 24 * 60 * 60;
    private static final int TAG_PREFERENCE_SCORE = 4;

    @Override
    @Transactional
    @ObservedBusiness(BusinessOperation.LIKE_CONTENT)
    public void likeContent(Long contentId) {
        Long userId = getUserId();
        access.readable(contentId, userId);

        List<Long> tagIds = contentTagMapper.selectList(new LambdaQueryWrapper<ContentTag>()
                        .select(ContentTag::getTagId)
                        .eq(ContentTag::getContentId, contentId)
                        .orderByAsc(ContentTag::getTagId))
                        .stream().map(ContentTag::getTagId).toList();
        try {
            contentLikeMapper.insert(new ContentLike()
                    .setUserId(userId)
                    .setContentId(contentId)
                    .setCreditedTagIds(JsonUtil.toJson(tagIds))
                    .setCreditedScore(TAG_PREFERENCE_SCORE));
        } catch (DuplicateKeyException alreadyLiked) {
            // 已点过赞, 直接返回; InnoDB 只回滚失败的这条 INSERT, 事务里其余写入不受影响
            return;
        }

        tagPreferences.addTagScore(userId, tagIds, TAG_PREFERENCE_SCORE);
        bufferLikeCountAfterCommit(contentId, 1);
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        return Long.valueOf(userId);
    }

    @Override
    @Transactional
    @ObservedBusiness(BusinessOperation.UNLIKE_CONTENT)
    public void cancelLikeContent(Long contentId) {
        Long userId = getUserId();
        // 与点赞同一条访问规则: 前端不为不可见作品提供撤赞入口, 后端两条路径统一把关
        access.readable(contentId, userId);

        ContentLike like = lambdaQuery()
                .eq(ContentLike::getUserId, userId)
                .eq(ContentLike::getContentId, contentId)
                .last("FOR UPDATE")
                .one();
        if (like == null || contentLikeMapper.deleteById(like.getId()) == 0) {
            return;
        }

        Long[] storedTagIds = JsonUtil.fromJson(like.getCreditedTagIds(), Long[].class);
        // JSON 字面量 null 视为空标签: 原标签不可知, 跳过标签扣分; 关系照常删除、计数照常扣减
        List<Long> tagIds = storedTagIds == null ? List.of() : List.of(storedTagIds);
        if (storedTagIds == null) {
            log.warn("撤赞记录 credited_tag_ids 为 JSON null, 跳过标签扣分 userId={} contentId={}", userId, contentId);
        } else {
            tagPreferences.addTagScore(userId, tagIds, -like.getCreditedScore());
        }
        bufferLikeCountAfterCommit(contentId, -1);
    }

    private void bufferLikeCountAfterCommit(Long contentId, int delta) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    counterBuffer.add(CounterType.CONTENT_LIKE, contentId, delta);
                } catch (RuntimeException failure) {
                    log.warn("点赞计数增量写入失败 contentId={}, delta={}", contentId, delta, failure);
                }
            }
        });
    }

    @Override
    public PageResult<LikedContentVO> getLikedContentList(Long userId, ContentPageQuery pageQuery) {
        Long viewerId = getUserId();
        pageQuery.mediaType();
        List<Long> blockedAuthors = blocks.blockedAuthors(viewerId);
        int total = contentLikeMapper.selectUserLikedCount(userId, viewerId, pageQuery.getType(), blockedAuthors);
        int totalPages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();
        int offset = (pageQuery.getPageNo() - 1) * pageQuery.getPageSize();
        List<Long> contentIds = contentLikeMapper.selectListByUserId(
                userId,
                viewerId,
                pageQuery.getType(),
                blockedAuthors,
                offset,
                pageQuery.getPageSize());
        if (contentIds == null || contentIds.isEmpty()) {
            return PageResult.of(total, totalPages, List.of());
        }

        // 返回顺序与点赞时间序一致
        List<ContentSummary> summaries = contentSummaryPort.listByIdsOrdered(
                new ContentSummaryPort.SummaryQuery(contentIds.stream().map(String::valueOf).toList(), viewerId));

        if (summaries.isEmpty()) return PageResult.of(total, totalPages, List.of());
        List<LikedContentVO> voList = summaries.stream()
                .map(s -> {
                    LikedContentVO vo = BeanUtil.toBean(s, LikedContentVO.class);
                    vo.setId(String.valueOf(s.getId()));
                    return vo;
                }).toList();
        List<Long> authorIds = voList.stream().map(LikedContentVO::getAuthorId).toList();
        List<UserSummary> authors = userPort.listByIds(authorIds);
        Map<Long, String> authorNicknames = UserSummaries.toNicknameMap(authors);
        Map<Long, String> authorAvatarUrls = UserSummaries.toAvatarMap(authors);
        voList.forEach(vo -> {
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), STORAGE_EXPIRE));
        });
        voList.forEach(vo -> {
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_EXPIRE));
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_EXPIRE));
        });
        List<Boolean> isFollows = followSnapshot.isFollowOnList(viewerId, authorIds);
        for (int i = 0; i < voList.size(); i++) {
            voList.get(i).setIsFollow(isFollows.get(i));
        }

        return PageResult.of(total, totalPages, voList);
    }

    @Override
    public Boolean getLikeStatus(Long contentId) {
        return lambdaQuery()
                .eq(ContentLike::getUserId, UserContext.getUserId())
                .eq(ContentLike::getContentId, contentId)
                .exists();
    }

    @Override
    @ObservedBusiness(BusinessOperation.LIKE_COMMENT)
    public void likeComment(Long commentId) {
        Long userId = getUserId();

        ContentComment comment = contentCommentService.getById(commentId);
        if (comment == null) throw new BizException(ErrorCode.NOT_FOUND, "评论不存在");
        access.readable(comment.getContentId(), userId);

        ContentCommentLike like = contentCommentLikeMapper.selectOne(new LambdaQueryWrapper<ContentCommentLike>()
                .eq(ContentCommentLike::getUserId, userId)
                .eq(ContentCommentLike::getCommentId, commentId));
        if (like != null && like.getIsLiked() == ContentLikeConstants.LIKE) {
            // 已经点过赞了直接返回; 顺手补一下 Redis 展示集合(ZSet 只有 10 天寿命, 过期后会误判成未点赞)
            markCommentLiked(userId, commentId);
            return;
        }

        // 点赞数只累加到 Redis
        counterBuffer.add(CounterType.COMMENT_LIKE, commentId, 1);
        if (like != null) {
            like.setIsLiked(ContentLikeConstants.LIKE);
            contentCommentLikeMapper.updateById(like);
        } else {
            contentCommentLikeMapper.insert(new ContentCommentLike()
                    .setUserId(userId).setCommentId(commentId).setIsLiked(ContentLikeConstants.LIKE));
        }
        markCommentLiked(userId, commentId);

        if (!Objects.equals(comment.getUserId(), userId)) {

            LikeCommentMessage lc = LikeCommentMessage.builder().senderId(userId).commentId(commentId).build();
            rocketMQTemplate.syncSend(CommentMessage.TOPIC + ":" + LikeCommentMessage.LIKE_TAG, lc);
        }
    }

    @Override
    @ObservedBusiness(BusinessOperation.UNLIKE_COMMENT)
    public void cancelLikeComment(Long commentId) {
        Long userId = getUserId();

        ContentCommentLike like = contentCommentLikeMapper.selectOne(new LambdaQueryWrapper<ContentCommentLike>()
                .eq(ContentCommentLike::getUserId, userId)
                .eq(ContentCommentLike::getCommentId, commentId));
        if (like == null || like.getIsLiked() == ContentLikeConstants.CANCEL_LIKE) {
            return;
        }

        ContentComment comment = contentCommentService.lambdaQuery()
            .eq(ContentComment::getId, commentId).select(ContentComment::getUserId).one();

        counterBuffer.add(CounterType.COMMENT_LIKE, commentId, -1);
        like.setIsLiked(ContentLikeConstants.CANCEL_LIKE);
        contentCommentLikeMapper.updateById(like);
        redisTemplate.opsForZSet().remove(likedCommentKey(userId), commentId.toString());

        if (comment != null && !Objects.equals(comment.getUserId(), userId)) {
            LikeCommentMessage lc = LikeCommentMessage.builder().senderId(userId).commentId(commentId).build();
            rocketMQTemplate.syncSend(CommentMessage.TOPIC + ":" + LikeCommentMessage.CANCEL_LIKE_TAG, lc);
        }
    }

    /**
     * 展示用「我赞过的评论」集合。
     */
    private void markCommentLiked(Long userId, Long commentId) {
        String key = likedCommentKey(userId);
        redisTemplate.opsForZSet().add(key, commentId.toString(), System.currentTimeMillis());
        redisTemplate.expire(key, LIKED_COMMENT_EXPIRE_DAYS, TimeUnit.DAYS);
        // 只留最近 100 条: 按 rank 淘汰
        redisTemplate.opsForZSet().removeRange(key, 0, -101);
    }
}

package cn.diaojiahong.myyoutube.comment.service.impl;

import cn.diaojiahong.myyoutube.comment.domain.dto.CommentDTO;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentPageQuery;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentVO;
import cn.diaojiahong.myyoutube.comment.mapper.ContentCommentMapper;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.mq.message.CommentMessage;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final IVideoService videoService;
    private final IContentService contentService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final static int STORAGE_EXPIRE = 60 * 20;

    @Transactional
    @Override
    public Result<Void> publishComment(CommentDTO commentDTO) {
        Long parentId = commentDTO.getParentId();
        if (parentId == null) {
            throw new IllegalArgumentException("父评论id不能为空");
        }
        Content content = contentService.getById(commentDTO.getVideoId());
        if (content == null) {
            throw new IllegalArgumentException("内容不存在");
        } else if (content.getType() == ContentTypeEnum.VIDEO) {
            // 评论开关是视频域的; 动态默认可评论
            Video video = videoService.getById(content.getId());
            if (video != null && video.getAllowComment() == 0) {
                throw new IllegalArgumentException("视频不允许评论");
            }
        }
        Long userId = getUserId();
        ContentComment videoComment = new ContentComment();
        videoComment.setUserId(userId);
        videoComment.setContentId(commentDTO.getVideoId());
        videoComment.setContent(commentDTO.getContent());
        videoComment.setParentId(parentId);

        ContentComment parent = getById(parentId);
        // 判断是否是一级评论
        if (parentId == 0) {
            videoComment.setRootId(null);
        } else {
            // 父评论
            if (parent == null || !CommentStatusEnum.VISIBLE.getCode().equals(parent.getStatus())) {
                throw new IllegalArgumentException("父评论不存在或不可回复");
            }
            if (!Objects.equals(parent.getContentId(), commentDTO.getVideoId())) {
                throw new IllegalArgumentException("父评论不属于该内容");
            }
            videoComment.setReplyUserId(parent.getUserId());
            // 找根评论id
            Long rootId = parent.getRootId() == null ? parent.getId() : parent.getRootId();
            videoComment.setRootId(rootId);
            // 回复数改自增
            lambdaUpdate().eq(ContentComment::getId, rootId)
                    .setIncrBy(ContentComment::getReplyCount, 1).update();
        }
        // 更新内容的评论数
        contentService.lambdaUpdate().eq(Content::getId, commentDTO.getVideoId())
                .setIncrBy(Content::getCommentCount, 1).update();
        saveOrUpdate(videoComment);

        // 发送Mq通知, 自己回复自己不通知
        CommentMessage message = null;
        boolean isSend = false;
        if (parentId == 0) {
            // 接受方是内容作者
            if (!userId.equals(content.getAuthorId())) {
                message = CommentMessage.builder()
                       .content(commentDTO.getContent()).contentId(content.getId())
                       .senderId(userId).receiverId(content.getAuthorId()).isContentComment(true)
                       .commentId(videoComment.getId()).build();
                isSend = true;
            }
        } else {
            // 接受方是父评论的用户
            if (!userId.equals(parent.getUserId())) {
                message = CommentMessage.builder()
                        .content(commentDTO.getContent()).contentId(content.getId())
                        .senderId(userId).receiverId(parent.getUserId()).isContentComment(false)
                        .commentId(videoComment.getId()).build();
                isSend = true;
            }
        }
        if (isSend) {
            rocketMQTemplate.syncSend(MqConstants.TOPIC_COMMENT_ACTION + ":" + MqConstants.TAG_PUBLISH, message);
        }

        return Result.success();
    }

    @Transactional
    @Override
    public Result<Void> deleteComment(Long commentId) {
        ContentComment videoComment = getById(commentId);
        if (videoComment == null) {
            throw new IllegalArgumentException("评论不存在");
        }
        Long userId = getUserId();
        if (!videoComment.getUserId().equals(userId)) {
            throw new IllegalArgumentException("用户没有权限删除评论");
        }
        videoComment.setStatus(CommentStatusEnum.DELETED.getCode());
        updateById(videoComment);
        // 本次真正被标记删除的条数
        long removed = 1;
        if (videoComment.getParentId() == 0) {
            // 删除所有回复评论; 已经 deleted 的不重复计数
            removed += baseMapper.update(null, new LambdaUpdateWrapper<ContentComment>()
                    .eq(ContentComment::getRootId, commentId)
                    .ne(ContentComment::getStatus, CommentStatusEnum.DELETED.getCode())
                    .set(ContentComment::getStatus, CommentStatusEnum.DELETED.getCode()));
        } else {
            // 更新回复数
            lambdaUpdate().eq(ContentComment::getId, videoComment.getRootId())
                    .gt(ContentComment::getReplyCount, 0)
                    .setIncrBy(ContentComment::getReplyCount, -1).update();
        }
        // 更新内容的评论数, 带护底避免减成负数
        contentService.lambdaUpdate().eq(Content::getId, videoComment.getContentId())
                .ge(Content::getCommentCount, removed)
                .setIncrBy(Content::getCommentCount, -removed).update();
        return Result.success();
    }

    @Override
    public Result<PageResult<CommentVO>> getCommentList(CommentPageQuery commentPageQuery) {
        // 视频评论 -> 根据点赞量排序(一样按创建时间排序)
        LambdaQueryWrapper<ContentComment> w = new LambdaQueryWrapper<ContentComment>()
                .eq(ContentComment::getContentId, commentPageQuery.getVideoId())
                .in(ContentComment::getStatus, CommentStatusEnum.VISIBLE.getCode(), CommentStatusEnum.BLOCKED.getCode())
                .orderByDesc(ContentComment::getLikeCount)
                .orderByDesc(ContentComment::getReplyCount);
        if (commentPageQuery.getParentId() == 0) {
            w.isNull(ContentComment::getRootId);
            w.orderByDesc(ContentComment::getCreateTime);
        } else {
            w.eq(ContentComment::getRootId, commentPageQuery.getRootId());
            w.orderByAsc(ContentComment::getCreateTime);
        }
        Page<ContentComment> page = page(commentPageQuery.toPage(), w);
        List<ContentComment> records = page.getRecords();
        // 转vo
        List<CommentVO> commentVOList = records.stream()
                .map(record -> BeanUtil.toBean(record, CommentVO.class)).toList();
        // 对违规的评论处理
        for (CommentVO commentVO : commentVOList) {
            if (commentVO.getStatus().equals(CommentStatusEnum.BLOCKED.getCode())) {
                commentVO.setContent("[评论违规,已被处理]");
            }
        }
        // 填充用户昵称
        // 填充用户头像url
        List<Long> userIds = commentVOList.stream().map(CommentVO::getUserId).toList();
        Map<Long, String> nicknames = userPort.getNicknames(userIds);
        Map<Long, String> avatarUrls = userPort.getAvatars(userIds);
        if (nicknames == null || nicknames.isEmpty() || avatarUrls == null || avatarUrls.isEmpty()) {
            return Result.success(PageResult.of(page.getTotal(), page.getPages(), commentVOList));
        }
        commentVOList.forEach(commentVO -> commentVO.setNickname(nicknames.get(commentVO.getUserId())));
        commentVOList.forEach(commentVO -> commentVO.setAvatarUrl(storageUtil.signGetUrl(avatarUrls.get(commentVO.getUserId()), STORAGE_EXPIRE)));

        // 填充回复用户的昵称
        List<Long> replyUserIds = commentVOList.stream().map(CommentVO::getReplyUserId).toList();
        Map<Long, String> replyNicknames = userPort.getNicknames(replyUserIds);
        if (replyNicknames != null && !replyNicknames.isEmpty()) {
            commentVOList.forEach(commentVO -> commentVO.setReplyNickname(replyNicknames.get(commentVO.getReplyUserId())));
        }

        // 查询用户是否点过赞
        Long userId = getUserId();
        if (!commentVOList.isEmpty()) {
            String key = RedisConstants.USER_LIKE_COMMENT + userId;
            StringRedisSerializer strSer = new StringRedisSerializer();
            byte[] rawKey = strSer.serialize(key);
            List<Object> scoreResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (CommentVO v : commentVOList) {
                    byte[] member = strSer.serialize(v.getId().toString());
                    connection.zScore(rawKey, member);
                }
                return null;
            });
            for (int i = 0; i < commentVOList.size(); i++) {
                Double score = (Double) scoreResults.get(i);
                commentVOList.get(i).setIsLike(score != null);
            }
        }

        return Result.success(PageResult.of(page.getTotal(), page.getPages(), commentVOList));
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("用户未登录");
        }
        return Long.valueOf(userId);
    }

}

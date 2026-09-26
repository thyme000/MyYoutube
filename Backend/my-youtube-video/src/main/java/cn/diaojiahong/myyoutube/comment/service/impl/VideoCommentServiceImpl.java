package cn.diaojiahong.myyoutube.comment.service.impl;

import cn.diaojiahong.myyoutube.comment.domain.dto.CommentDTO;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentPageQuery;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentVO;
import cn.diaojiahong.myyoutube.comment.mapper.VideoCommentMapper;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.mq.message.CommentMessage;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
public class VideoCommentServiceImpl extends ServiceImpl<VideoCommentMapper, VideoComment> implements IVideoCommentService {

    private final IUserService userService;
    private final S3StorageUtil storageUtil;
    private final IVideoService videoService;
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
        Video video = videoService.getById(commentDTO.getVideoId());
        if (video == null) {
            throw new IllegalArgumentException("视频不存在");
        } else if (video.getAllowComment() == 0) {
            throw new IllegalArgumentException("视频不允许评论");
        }
        Long userId = getUserId();
        VideoComment videoComment = new VideoComment();
        videoComment.setUserId(userId);
        videoComment.setVideoId(commentDTO.getVideoId());
        videoComment.setContent(commentDTO.getContent());
        videoComment.setParentId(parentId);

        VideoComment parent = getById(parentId);
        // 判断是否是一级评论
        if (parentId == 0) {
            videoComment.setRootId(null);
        } else {
            // 父评论
            if (parent == null || !CommentStatusEnum.VISIBLE.getCode().equals(parent.getStatus())) {
                throw new IllegalArgumentException("父评论不存在或不可回复");
            }
            if (!Objects.equals(parent.getVideoId(), commentDTO.getVideoId())) {
                throw new IllegalArgumentException("父评论不属于该视频");
            }
            videoComment.setReplyUserId(parent.getUserId());
            // 找根评论id
            if (parent.getRootId() == null) {
                // 父评论是一级评论
                videoComment.setRootId(parent.getId());
                // 更新评论数
                parent.setReplyCount(parent.getReplyCount() + 1);
                updateById(parent);
            } else {
                // 父评论不是一级评论
                videoComment.setRootId(parent.getRootId());
                // 不是一级评论,更新根评论的回复数
                VideoComment root = getById(parent.getRootId());
                root.setReplyCount(root.getReplyCount() + 1);
                updateById(root);
            }
        }
        // 更新视频的评论数
        videoService.lambdaUpdate().eq(Video::getId, commentDTO.getVideoId())
                .setIncrBy(Video::getCommentCount, 1).update();
        saveOrUpdate(videoComment);

        // 发送Mq通知, 自己回复自己不通知
        CommentMessage message = null;
        boolean isSend = false;
        if (parentId == 0) {
            // 接受方是视频作者
            if (!userId.equals(video.getAuthorId())) {
                message = CommentMessage.builder()
                       .content(commentDTO.getContent()).videoId(video.getId())
                       .senderId(userId).receiverId(video.getAuthorId()).isVideoComment(true)
                       .commentId(videoComment.getId()).build();
                isSend = true;
            }
        } else {
            // 接受方是父评论的用户
            if (!userId.equals(parent.getUserId())) {
                message = CommentMessage.builder()
                        .content(commentDTO.getContent()).videoId(video.getId())
                        .senderId(userId).receiverId(parent.getUserId()).isVideoComment(false)
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
        VideoComment videoComment = getById(commentId);
        if (videoComment == null) {
            throw new IllegalArgumentException("评论不存在");
        }
        Long userId = getUserId();
        if (!videoComment.getUserId().equals(userId)) {
            throw new IllegalArgumentException("用户没有权限删除评论");
        }
        videoComment.setStatus(CommentStatusEnum.DELETED.getCode());
        updateById(videoComment);
        if (videoComment.getParentId() == 0) {
            // 删除所有回复评论
            lambdaUpdate()
                    .eq(VideoComment::getRootId, commentId)
                    .set(VideoComment::getStatus, CommentStatusEnum.DELETED.getCode())
                    .update();
        } else {
            // 更新回复数
            lambdaUpdate().eq(VideoComment::getId, videoComment.getRootId())
                    .gt(VideoComment::getReplyCount, 0)
                    .setIncrBy(VideoComment::getReplyCount, -1).update();
        }
        // 更新视频评论数
        videoService.lambdaUpdate().eq(Video::getId, videoComment.getVideoId())
                .setIncrBy(Video::getCommentCount, -1).update();
        return Result.success();
    }

    @Override
    public Result<PageResult<CommentVO>> getCommentList(CommentPageQuery commentPageQuery) {
        // 视频评论 -> 根据点赞量排序(一样按创建时间排序)
        LambdaQueryWrapper<VideoComment> w = new LambdaQueryWrapper<VideoComment>()
                .eq(VideoComment::getVideoId, commentPageQuery.getVideoId())
                .in(VideoComment::getStatus, CommentStatusEnum.VISIBLE.getCode(), CommentStatusEnum.BLOCKED.getCode())
                .orderByDesc(VideoComment::getLikeCount)
                .orderByDesc(VideoComment::getReplyCount);
        if (commentPageQuery.getParentId() == 0) {
            w.isNull(VideoComment::getRootId);
            w.orderByDesc(VideoComment::getCreateTime);
        } else {
            w.eq(VideoComment::getRootId, commentPageQuery.getRootId());
            w.orderByAsc(VideoComment::getCreateTime);
        }
        Page<VideoComment> page = page(commentPageQuery.toPage(), w);
        List<VideoComment> records = page.getRecords();
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
        Map<Long, String> nicknames = userService.getAuthorNicknames(userIds);
        Map<Long, String> avatarUrls = userService.getAuthorAvatarUrls(userIds);
        if (nicknames == null || nicknames.isEmpty() || avatarUrls == null || avatarUrls.isEmpty()) {
            return Result.success(PageResult.of(page.getTotal(), page.getPages(), commentVOList));
        }
        commentVOList.forEach(commentVO -> commentVO.setNickname(nicknames.get(commentVO.getUserId())));
        commentVOList.forEach(commentVO -> commentVO.setAvatarUrl(storageUtil.signGetUrl(avatarUrls.get(commentVO.getUserId()), STORAGE_EXPIRE)));

        // 填充回复用户的昵称
        List<Long> replyUserIds = commentVOList.stream().map(CommentVO::getReplyUserId).toList();
        Map<Long, String> replyNicknames = userService.getAuthorNicknames(replyUserIds);
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

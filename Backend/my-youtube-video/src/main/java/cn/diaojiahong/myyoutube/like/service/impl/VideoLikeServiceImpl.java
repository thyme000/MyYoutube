package cn.diaojiahong.myyoutube.like.service.impl;

import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.like.domain.constants.VideoLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.VideoCommentLike;
import cn.diaojiahong.myyoutube.like.domain.po.VideoLike;
import cn.diaojiahong.myyoutube.like.domain.vo.LikeVideoVO;
import cn.diaojiahong.myyoutube.like.mapper.VideoCommentLikeMapper;
import cn.diaojiahong.myyoutube.like.mapper.VideoLikeMapper;
import cn.diaojiahong.myyoutube.like.service.IVideoLikeService;
import cn.diaojiahong.myyoutube.mq.message.LikeCommentMessage;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 视频点赞表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoLikeServiceImpl extends ServiceImpl<VideoLikeMapper, VideoLike> implements IVideoLikeService {

    private final IVideoService videoService;
    private final StringRedisTemplate redisTemplate;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final VideoLikeMapper videoLikeMapper;
    private final VideoCommentLikeMapper videoCommentLikeMapper;
    private final IVideoCommentService videoCommentService;
    private final RocketMQTemplate rocketMQTemplate;
    private final IUserFollowService userFollowService;
    private final CounterBuffer counterBuffer;

    // 24小时后过期
    private static final int STORAGE_EXPIRE = 24 * 60 * 60;

    @Override
    @Transactional
    public Result<Void> likeVideo(Long videoId) {
        // 获取用户id
        Long userId = getUserId();

        // 判断用户是否已经点过赞
        VideoLike like = lambdaQuery()
                .eq(VideoLike::getUserId, userId)
                .eq(VideoLike::getVideoId, videoId).one();
        if (like != null && like.getIsLiked() == VideoLikeConstants.LIKE) {
            // 已经点过赞了直接返回
            return Result.success();
        }

        // 作者 id 必须在写计数之前取: 原先排在 UPDATE video 之后, 等于把这次额外查询也算进热点行的锁持有时间
        Long authorId = findAuthorId(videoId);

        // 点赞数只累加到 Redis, 由落库任务合并成一次 UPDATE
        counterBuffer.add(CounterType.VIDEO_LIKE, videoId, 1);

        // 保存点赞记录
        if (like != null) {
            // 已有记录，更新
            like.setIsLiked(VideoLikeConstants.LIKE);
            updateById(like);
        } else {
            // 没有记录，新增
            save(new VideoLike().setUserId(userId).setVideoId(videoId).setIsLiked(VideoLikeConstants.LIKE));
        }
        // 更新作者获赞量
        counterBuffer.add(CounterType.USER_LIKE, authorId, 1);

        return Result.success();
    }

    private Long findAuthorId(Long videoId) {
        Video video = videoService.lambdaQuery().eq(Video::getId, videoId).select(Video::getAuthorId).one();
        if (video == null) {
            throw new BizException(BizException.USER_ERROR, "视频不存在");
        }
        return video.getAuthorId();
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }

    @Override
    @Transactional
    public Result<Void> cancelLikeVideo(Long videoId) {
        // 获取用户id
        Long userId = getUserId();

        // 判断用户是否点过赞
        VideoLike like = lambdaQuery()
                .eq(VideoLike::getUserId, userId)
                .eq(VideoLike::getVideoId, videoId)
                .one();
        if (like == null || like.getIsLiked() == VideoLikeConstants.CANCEL_LIKE) {
            // 未点赞或已取消点赞直接返回
            return Result.success();
        }

        Long authorId = findAuthorId(videoId);

        counterBuffer.add(CounterType.VIDEO_LIKE, videoId, -1);

        // 修改点赞记录
        like.setIsLiked(VideoLikeConstants.CANCEL_LIKE);
        updateById(like);

        // 更新用户获赞量
        counterBuffer.add(CounterType.USER_LIKE, authorId, -1);

        return Result.success();
    }

    @Override
    public Result<Void> dislikeVideo(Long videoId) {
        Long userId = getUserId();

        // 用户不喜欢的视频默认存储在redis中
        redisTemplate.opsForSet().add(RedisConstants.USER_DISLIKE + userId, videoId.toString());
        redisTemplate.expire(RedisConstants.USER_DISLIKE + userId, RedisConstants.USER_DISLIKE_EXPIRE, TimeUnit.DAYS);
        return Result.success();
    }

    @Override
    public Result<PageResult<LikeVideoVO>> getLikeVideoList(Long userId, PageQuery pageQuery) {
        // 查询用户点过赞的视频id
        int offset = (pageQuery.getPageNo() - 1) * pageQuery.getPageSize();
        List<Long> videoIds = videoLikeMapper.selectListByUserId(userId, offset, pageQuery.getPageSize());
        if (videoIds == null || videoIds.isEmpty()) {
            // 没有点过赞的视频,直接返回空列表
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        // 查询视频列表
        List<Video> videoList = videoService.lambdaQuery()
                .eq(Video::getIsDelete, 0)
                .eq(Video::getVisibility, VideoVisibilityEnum.PUBLIC)
                .eq(Video::getStatus, VideoStatusEnum.PUBLISHED)
                .in(Video::getId, videoIds)
                .list();

        Map<Long, Video> videoMap = videoList.stream()
                .collect(Collectors.toMap(Video::getId, Function.identity()));
        List<Video> orderedVideoList = videoIds.stream()
                .map(videoMap::get)
                .filter(Objects::nonNull)  // 过滤掉不存在的记录
                .toList();

        // 转换成vo
        List<LikeVideoVO> voList = orderedVideoList.stream()
                .map(video -> BeanUtil.toBean(video, LikeVideoVO.class)).toList();
        // 填充作者昵称和头像url
        List<Long> authorIds = voList.stream().map(LikeVideoVO::getAuthorId).toList();
        Map<Long, String> authorNicknames = userPort.getNicknames(authorIds);
        Map<Long, String> authorAvatarUrls = userPort.getAvatars(authorIds);
        voList.forEach(vo -> {
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), STORAGE_EXPIRE));
        });
        // 填充视频封面url和视频url
        voList.forEach(vo -> {
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_EXPIRE));
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_EXPIRE));
        });
        // 填充是否关注作者
        List<Boolean> isFollows = userFollowService.isFollowOnList(authorIds, userId);
        for (int i = 0; i < voList.size(); i++) {
            voList.get(i).setIsFollow(isFollows.get(i));
        }

        int total = videoLikeMapper.selectUserLikedCount(userId);
        int totalPages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();

        return Result.success(PageResult.of(total, totalPages, voList));
    }

    @Override
    public Result<Boolean> getLikeStatus(Long videoId) {
        // 查询用户是否点过赞
        VideoLike like = lambdaQuery()
                .eq(VideoLike::getUserId, UserContext.getUserId())
                .eq(VideoLike::getVideoId, videoId)
                .one();
        return Result.success(like != null && like.getIsLiked() == VideoLikeConstants.LIKE);
    }

    @Override
    public Result<Void> likeComment(Long commentId) {
        // 获取用户id
        Long userId = getUserId();

        VideoCommentLike like = videoCommentLikeMapper.selectOne(new LambdaQueryWrapper<VideoCommentLike>()
                .eq(VideoCommentLike::getUserId, userId)
                .eq(VideoCommentLike::getCommentId, commentId));
        if (like != null && like.getIsLiked() == VideoLikeConstants.LIKE) {
            // 已经点过赞了直接返回; 顺手补一下 Redis 展示集合(ZSet 只有 10 天寿命, 过期后会误判成未点赞)
            markCommentLiked(userId, commentId);
            return Result.success();
        }

        VideoComment comment = videoCommentService.lambdaQuery()
                .eq(VideoComment::getId, commentId).select(VideoComment::getUserId).one();
        if (comment == null) {
            throw new BizException(BizException.USER_ERROR, "评论不存在");
        }

        // 点赞数只累加到 Redis
        counterBuffer.add(CounterType.COMMENT_LIKE, commentId, 1);
        if (like != null) {
            like.setIsLiked(VideoLikeConstants.LIKE);
            videoCommentLikeMapper.updateById(like);
        } else {
            videoCommentLikeMapper.insert(new VideoCommentLike()
                    .setUserId(userId).setCommentId(commentId).setIsLiked(VideoLikeConstants.LIKE));
        }
        markCommentLiked(userId, commentId);

        if (!Objects.equals(comment.getUserId(), userId)) {
            // 发送点赞通知(MQ)
            LikeCommentMessage lc = LikeCommentMessage
                    .builder().senderId(userId).commentId(commentId).build();
            rocketMQTemplate.syncSend(MqConstants.TOPIC_COMMENT_ACTION + ":" + MqConstants.TAG_LIKE, lc);
        }

        return Result.success();
    }

    @Override
    public Result<Void> cancelLikeComment(Long commentId) {
        // 获取用户id
        Long userId = getUserId();

        VideoCommentLike like = videoCommentLikeMapper.selectOne(new LambdaQueryWrapper<VideoCommentLike>()
                .eq(VideoCommentLike::getUserId, userId)
                .eq(VideoCommentLike::getCommentId, commentId));
        if (like == null || like.getIsLiked() == VideoLikeConstants.CANCEL_LIKE) {
            // 未点赞或已取消, 直接返回
            return Result.success();
        }

        counterBuffer.add(CounterType.COMMENT_LIKE, commentId, -1);
        like.setIsLiked(VideoLikeConstants.CANCEL_LIKE);
        videoCommentLikeMapper.updateById(like);
        redisTemplate.opsForZSet().remove(RedisConstants.USER_LIKE_COMMENT + userId, commentId.toString());

        // 发送取消点赞通知(MQ)
        VideoComment comment = videoCommentService.lambdaQuery()
            .eq(VideoComment::getId, commentId).select(VideoComment::getUserId).one();
        if (comment != null && !Objects.equals(comment.getUserId(), userId)) {
            LikeCommentMessage lc = LikeCommentMessage
                    .builder().senderId(userId).commentId(commentId).build();
            rocketMQTemplate.syncSend(MqConstants.TOPIC_COMMENT_ACTION + ":" + MqConstants.TAG_UNLIKE, lc);
        }

        return Result.success();
    }

    /**
     * 展示用「我赞过的评论」集合。
     */
    private void markCommentLiked(Long userId, Long commentId) {
        String key = RedisConstants.USER_LIKE_COMMENT + userId;
        redisTemplate.opsForZSet().add(key, commentId.toString(), System.currentTimeMillis());
        redisTemplate.expire(key, RedisConstants.USER_LIKE_COMMENT_EXPIRE, TimeUnit.DAYS);
        // 只留最近 100 条: 按 rank 淘汰
        redisTemplate.opsForZSet().removeRange(key, 0, -101);
    }
}

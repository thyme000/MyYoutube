package cn.diaojiahong.myyoutube.like.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.like.domain.constants.VideoLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.VideoLike;
import cn.diaojiahong.myyoutube.like.domain.vo.LikeVideoVO;
import cn.diaojiahong.myyoutube.like.mapper.VideoLikeMapper;
import cn.diaojiahong.myyoutube.like.service.IVideoLikeService;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final IUserService userService;
    private final AliOssUtil aliOssUtil;
    private final VideoLikeMapper videoLikeMapper;
    // 24小时后过期
    private static final int OSS_EXPIRE = 24;

    @Override
    @Transactional
    public Result<Void> likeVideo(Long videoId) {
        // 获取用户id
        UserDTO user = UserContext.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();

        // 判断用户是否已经点过赞
        VideoLike like = lambdaQuery()
                .eq(VideoLike::getUserId, userId)
                .eq(VideoLike::getVideoId, videoId).one();
        if (like != null && like.getIsLiked() == VideoLikeConstants.LIKE) {
            // 已经点过赞了直接返回
            return Result.success();
        }

        // 修改视频的点赞数
        videoService.lambdaUpdate().eq(Video::getId, videoId).setIncrBy(Video::getLikeCount, 1).update();

        // 保存点赞记录
        if (like != null) {
            // 已有记录，更新
            like.setIsLiked(VideoLikeConstants.LIKE);
            updateById(like);
        } else {
            // 没有记录，新增
            save(new VideoLike().setUserId(userId).setVideoId(videoId).setIsLiked(VideoLikeConstants.LIKE));
        }
        return Result.success();
    }

    @Override
    @Transactional
    public Result<Void> cancelLikeVideo(Long videoId) {
        // 获取用户id
        UserDTO user = UserContext.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();

        // 判断用户是否点过赞
        VideoLike like = lambdaQuery()
                .eq(VideoLike::getUserId, userId)
                .eq(VideoLike::getVideoId, videoId)
                .one();
        if (like == null || like.getIsLiked() == VideoLikeConstants.CANCEL_LIKE) {
            // 未点赞或已取消点赞直接返回
            return Result.success();
        }

        // 修改视频点赞数
        videoService.lambdaUpdate()
                .gt(Video::getLikeCount, 0)
                .eq(Video::getId, videoId).setIncrBy(Video::getLikeCount, -1).update();

        // 修改点赞记录
        like.setIsLiked(VideoLikeConstants.CANCEL_LIKE);
        updateById(like);

        return Result.success();
    }

    @Override
    public Result<Void> dislikeVideo(Long videoId) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();

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
        Map<Long, String> authorNicknames = userService.getAuthorNicknames(authorIds);
        Map<Long, String> authorAvatarUrls = userService.getAuthorAvatarUrls(authorIds);
        voList.forEach(vo -> {
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(aliOssUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), OSS_EXPIRE));
        });
        // 填充视频封面url和视频url
        voList.forEach(vo -> {
            vo.setCoverUrl(aliOssUtil.signGetUrl(vo.getCoverUrl(), OSS_EXPIRE));
            vo.setVideoUrl(aliOssUtil.signGetUrl(vo.getVideoUrl(), OSS_EXPIRE));
        });

        int total = videoLikeMapper.selectUserLikedCount(userId);
        int totalPages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();

        return Result.success(PageResult.of(total, totalPages, voList));
    }
}

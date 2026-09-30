package cn.diaojiahong.myyoutube.like.counter;

import cn.diaojiahong.myyoutube.common.counter.CounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.like.domain.constants.VideoLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.VideoLike;
import cn.diaojiahong.myyoutube.like.mapper.VideoLikeMapper;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * video.like_count 的落库目标。权威值是 video_like 的状态机，不是计数器自己的值。
 */
@Component
@RequiredArgsConstructor
public class VideoLikeCounterTarget implements CounterTarget {

    private final IVideoService videoService;
    private final VideoLikeMapper videoLikeMapper;

    @Override
    public CounterType type() {
        return CounterType.VIDEO_LIKE;
    }

    @Override
    public void applyDelta(long videoId, long delta) {
        LambdaUpdateChainWrapper<Video> wrapper = videoService.lambdaUpdate()
                .eq(Video::getId, videoId)
                .setIncrBy(Video::getLikeCount, delta);
        if (delta < 0) {
            // 减负护底: 只有当前值够减才落。替代原先挂在写路径上的 >0 守卫
            wrapper.ge(Video::getLikeCount, -delta);
        }
        wrapper.update();
    }

    @Override
    public Long authoritativeValue(long videoId) {
        return videoLikeMapper.selectCount(new LambdaQueryWrapper<VideoLike>()
                .eq(VideoLike::getVideoId, videoId)
                .eq(VideoLike::getIsLiked, VideoLikeConstants.LIKE));
    }

    @Override
    public void overwrite(long videoId, long value) {
        videoService.lambdaUpdate()
                .eq(Video::getId, videoId)
                .set(Video::getLikeCount, value)
                .update();
    }
}

package cn.diaojiahong.myyoutube.common.schedule;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoPlayCountScheduleJob {

    private final StringRedisTemplate redisTemplate;
    private final IVideoService videoService;

    /**
     * 每小时更新一次播放量：分片刷,避免单 Hash (热点key)过大,影响性能
     * {@link RedisConstants#videoPlayCountShardKey(int)}。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void updateVideoPlayCount() {
        for (int s = 0; s < RedisConstants.VIDEO_PLAY_COUNT_SHARD_COUNT; s++) {
            flushPendingHash(RedisConstants.videoPlayCountShardKey(s));
        }
    }

    private void flushPendingHash(String redisHashKey) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisHashKey);
        if (entries.isEmpty()) {
            return;
        }
        for (Object videoId : entries.keySet()) {
            Long playCount = Long.parseLong(entries.get(videoId).toString());
            boolean success = videoService.lambdaUpdate()
                    .setIncrBy(Video::getPlayCount, playCount)
                    .eq(Video::getId, videoId.toString())
                    .update();
            if (success) {
                redisTemplate.opsForHash().delete(redisHashKey, videoId);
            } else {
                log.warn("更新视频播放量失败, redisKey={}, 视频id:{}", redisHashKey, videoId);
            }
        }
    }
}

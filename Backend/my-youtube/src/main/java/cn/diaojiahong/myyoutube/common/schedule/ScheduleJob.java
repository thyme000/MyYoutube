package cn.diaojiahong.myyoutube.common.schedule;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleJob {

    private final StringRedisTemplate redisTemplate;
    private final IVideoService videoService;
    private final IUserService userService;

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

    /**
     * 每周日执行一次
     * 清理用户已过期的观看历史记录
     */
    @Scheduled(cron = "0 0 3 * * 0")
    public void cleanUserExpiredVideoRecord() {
        // 计算过期时间
        long ts = System.currentTimeMillis();
        long end = ts - 30L * 24 * 3600 * 1000;
        // 清理过期记录
        // 使用SCAN遍历清除所有用户的过期的播放记录
        ScanOptions options = ScanOptions.scanOptions().match(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + "*").build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String zsetKey = cursor.next();
                String userId = zsetKey.split(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP)[1];
                String hashKey = RedisConstants.USER_PLAYBACK_RESUME + userId;
                // 获取过期的视频记录(id)
                Set<String> videoIds = redisTemplate.opsForZSet().rangeByScore(zsetKey, 0, end);
                // 清理过期记录
                redisTemplate.opsForZSet().removeRangeByScore(zsetKey, 0, end);
                if (videoIds == null || videoIds.isEmpty()) {
                    return;
                }
                // 清理过期的视频记录
                redisTemplate.opsForHash().delete(hashKey, videoIds);
            }
        }
    }

    /**
     * 每周日执行一次
     * 清理用户已过期的短视频观看记录
     */
    @Scheduled(cron = "0 0 4 * * 0")
    public void cleanUserExpiredShortsRecord() {
        // 计算过期时间
        long ts = System.currentTimeMillis();
        long end = ts - 30L * 24 * 3600 * 1000;
        // 清理过期记录
        // 使用SCAN遍历清除所有用户的过期的播放记录
        ScanOptions options = ScanOptions.scanOptions().match(RedisConstants.USER_SHORTS_PLAY + "*").build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String zsetKey = cursor.next();
                // 获取过期的短视频记录(id)
                Set<String> shortsIds = redisTemplate.opsForZSet().rangeByScore(zsetKey, 0, end);
                if (shortsIds == null || shortsIds.isEmpty()) {
                    return;
                }
                // 清理过期记录
                redisTemplate.opsForZSet().removeRangeByScore(zsetKey, 0, end);
            }
        }
    }

    /**
     * 每10分钟执行一次
     * 更新用户获赞量
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void updateUserLikeCount() {
        ScanOptions options = ScanOptions.scanOptions().match(RedisConstants.USER_LIKE_COUNT + "*").build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String hashKey = cursor.next();
                String userId = hashKey.split(RedisConstants.USER_LIKE_COUNT)[1];
                Map<Object, Object> entries = redisTemplate.opsForHash().entries(hashKey);
                if (entries.isEmpty()) {
                    continue;
                }
                entries.forEach((videoId, likeCount) -> {
                    Long likeCountLong = Long.parseLong(likeCount.toString());
                    userService.lambdaUpdate()
                            .setIncrBy(User::getLikeCount, likeCountLong)
                            .eq(User::getId, userId)
                            .update();
                });
                // 删除redisKey
                redisTemplate.opsForHash().delete(hashKey);
            }
        }
    }
}

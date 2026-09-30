package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.search.api.VideoDocPort;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * video 域定时任务: 计数落库与对账 / 播放量落库 / 观看历史清理 / MySQL→ES 计数同步 / 热搜清理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleJob {

    private static final int ES_COUNT_SYNC_PAGE_SIZE = 500;

    /**
     * 播放量原子认领
     */
    private static final RedisScript<String> CLAIM_PLAY_COUNT_SCRIPT =
            RedisScript.of(new ClassPathResource("lua/claim-play-count.lua"), String.class);

    private final StringRedisTemplate redisTemplate;
    private final IVideoService videoService;
    private final VideoDocPort videoDocPort;
    private final CounterFlushService counterFlushService;
    private final ScheduleRoundLock roundLock;

    /**
     * 每 30s 把点赞缓冲落库。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void flushVideoCounters() {
        roundLock.run("schedule:lock:video:counter:flush", counterFlushService::flushRound);
    }

    /**
     * 每小时用 COUNT(*) 无条件覆盖一次点赞数, 抹平任意方向的累积误差
     */
    @Scheduled(cron = "0 20 * * * ?")
    public void reconcileVideoCounters() {
        roundLock.run("schedule:lock:video:counter:reconcile", counterFlushService::reconcileRound);
    }

    /**
     * 每小时更新一次播放量：分片刷,避免单 Hash (热点key)过大,影响性能
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void updateVideoPlayCount() {
        roundLock.run("schedule:lock:video:play-count", () -> {
            for (int s = 0; s < RedisConstants.VIDEO_PLAY_COUNT_SHARD_COUNT; s++) {
                flushPendingHash(RedisConstants.videoPlayCountShardKey(s));
            }
        });
    }

    private void flushPendingHash(String redisHashKey) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisHashKey);
        if (entries.isEmpty()) {
            return;
        }
        for (Object videoId : entries.keySet()) {
            String claimed = redisTemplate.execute(CLAIM_PLAY_COUNT_SCRIPT,
                    List.of(redisHashKey), videoId.toString());
            if (claimed == null) {
                continue;
            }
            long playCount = Long.parseLong(claimed);
            boolean success = videoService.lambdaUpdate()
                    .setIncrBy(Video::getPlayCount, playCount)
                    .eq(Video::getId, videoId.toString())
                    .update();
            if (!success) {
                log.warn("更新视频播放量失败, redisKey={}, 视频id:{}", redisHashKey, videoId);
            }
        }
    }

    /**
     * 每周日执行一次, 清理用户已过期的长视频观看记录
     */
    @Scheduled(cron = "0 0 3 * * 0")
    public void cleanUserExpiredVideoRecord() {
        long end = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        ScanOptions options = ScanOptions.scanOptions().match(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + "*").build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String zsetKey = cursor.next();
                String userId = zsetKey.split(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP)[1];
                String hashKey = RedisConstants.USER_PLAYBACK_RESUME + userId;
                Set<String> videoIds = redisTemplate.opsForZSet().rangeByScore(zsetKey, 0, end);
                redisTemplate.opsForZSet().removeRangeByScore(zsetKey, 0, end);
                if (videoIds == null || videoIds.isEmpty()) {
                    continue;
                }
                redisTemplate.opsForHash().delete(hashKey, videoIds);
            }
        }
    }

    /**
     * 每周日执行一次, 清理用户已过期的短视频观看记录
     */
    @Scheduled(cron = "0 0 4 * * 0")
    public void cleanUserExpiredShortsRecord() {
        long end = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        ScanOptions options = ScanOptions.scanOptions().match(RedisConstants.USER_SHORTS_PLAY + "*").build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String zsetKey = cursor.next();
                redisTemplate.opsForZSet().removeRangeByScore(zsetKey, 0, end);
            }
        }
    }

    /**
     * 每 30 分钟把 MySQL video.play_count / like_count 同步到 ES(推荐打分用)。
     * 以 MySQL 为准; Redis 记录上次扫到的最大 id, 游标滚动, 避免单次全表扫描。
     */
    @Scheduled(cron = "0 */30 * * * *")
    public void flushLikeCountAndPlayCount4ES() {
        roundLock.run("schedule:lock:video:es-count-sync", this::syncCountsToEs);
    }

    private void syncCountsToEs() {
        long lastId = readEsSyncCursor();
        List<Video> list = videoService.lambdaQuery()
                .eq(Video::getIsDelete, 0)
                .gt(Video::getId, lastId)
                .orderByAsc(Video::getId)
                .list()
                .stream()
                .limit(ES_COUNT_SYNC_PAGE_SIZE)
                .toList();
        if (list.isEmpty()) {
            redisTemplate.opsForValue().set(RedisConstants.SCHEDULE_ES_VIDEO_COUNT_SYNC_CURSOR, "0");
            return;
        }
        for (Video v : list) {
            try {
                videoDocPort.syncPlayLike(v.getId(), v.getPlayCount() != null ? v.getPlayCount() : 0L,
                        v.getLikeCount() != null ? v.getLikeCount() : 0L);
            } catch (Exception e) {
                log.warn("同步 ES 播放量/点赞量失败, videoId={}", v.getId(), e);
            }
        }
        long maxId = list.get(list.size() - 1).getId();
        if (list.size() < ES_COUNT_SYNC_PAGE_SIZE) {
            redisTemplate.opsForValue().set(RedisConstants.SCHEDULE_ES_VIDEO_COUNT_SYNC_CURSOR, "0");
        } else {
            redisTemplate.opsForValue().set(RedisConstants.SCHEDULE_ES_VIDEO_COUNT_SYNC_CURSOR, String.valueOf(maxId));
        }
    }

    private long readEsSyncCursor() {
        String v = redisTemplate.opsForValue().get(RedisConstants.SCHEDULE_ES_VIDEO_COUNT_SYNC_CURSOR);
        if (v == null || v.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 每周一0点0分清空热搜榜
     */
    @Scheduled(cron = "0 0 0 * * 1")
    public void clearHotSearch() {
        redisTemplate.delete(RedisConstants.HOT_SEARCH);
    }
}

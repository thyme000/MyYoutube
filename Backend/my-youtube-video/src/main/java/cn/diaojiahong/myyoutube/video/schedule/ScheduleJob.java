package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.counter.AuthorLikeCountService;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * video 域定时任务: 计数落库与对账 / 播放量落库 / 观看历史清理 / MySQL→ES 计数同步
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
    private final IContentService contentService;
    private final ContentDocPort contentDocPort;
    private final CounterFlushService counterFlushService;
    private final ScheduleRoundLock roundLock;
    private final MeterRegistry metrics;
    private final AuthorLikeCountService authorLikeCounts;

    /**
     * 每 5s 把点赞、收藏、评论赞缓冲落库。上一轮结束后再等，避免慢轮次叠在一起。
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void flushContentCounters() {
        roundLock.run(VideoScheduleLocks.COUNTER_FLUSH, this::flushAndPushReceivedLikes);
    }

    private void flushAndPushReceivedLikes() {
        try {
            counterFlushService.flushRound();
        } finally {
            authorLikeCounts.pushPending();
        }
    }

    /**
     * 每小时用 COUNT(*) 无条件覆盖一次点赞数, 抹平任意方向的累积误差
     */
    @Scheduled(cron = "0 20 * * * ?")
    public void reconcileContentCounters() {
        roundLock.run(VideoScheduleLocks.COUNTER_RECONCILE, this::reconcileAndPushReceivedLikes);
    }

    private void reconcileAndPushReceivedLikes() {
        try {
            counterFlushService.reconcileRound();
        } finally {
            authorLikeCounts.pushPending();
        }
    }

    /**
     * 按配置间隔将分片播放增量落库，每轮完成后再等待，避免慢轮次连续追赶。
     */
    @Scheduled(fixedDelayString = "${my-youtube.play-count.flush-interval-ms:60000}",
            initialDelayString = "${my-youtube.play-count.flush-interval-ms:60000}")
    public void updateVideoPlayCount() {
        roundLock.run(VideoScheduleLocks.PLAY_COUNT_FLUSH, this::flushPlayCounts);
    }

    private void flushPlayCounts() {
        long started = System.nanoTime();
        try {
            for (int s = 0; s < RedisConstants.VIDEO_PLAY_COUNT_SHARD_COUNT; s++) {
                flushPendingHash(RedisConstants.videoPlayCountShardKey(s));
            }
        } catch (RuntimeException failure) {
            // 中断整轮的异常只计一次；单条 update 返回 false 的失败在条目处计数。
            recordPlayCountMetric(MetricNames.CONTENT_PLAY_COUNT_FLUSH_FAILED);
            throw failure;
        } finally {
            try {
                metrics.timer(MetricNames.CONTENT_PLAY_COUNT_FLUSH_DURATION)
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } catch (RuntimeException metricsUnavailable) {
                /* 指标故障不能改变本轮结果。 */
            }
        }
    }

    private void flushPendingHash(String redisHashKey) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisHashKey);
        if (entries.isEmpty()) {
            return;
        }

        for (Object contentId : entries.keySet()) {
            String claimed = redisTemplate.execute(CLAIM_PLAY_COUNT_SCRIPT,
                    List.of(redisHashKey), contentId.toString());
            if (claimed == null) {
                continue;
            }
            // 已认领并开始处理的内容条数，包含后续写入失败；空增量不计数。
            recordPlayCountMetric(MetricNames.CONTENT_PLAY_COUNT_FLUSH_PROCESSED);
            long playCount = Long.parseLong(claimed);
            boolean success = contentService.lambdaUpdate()
                    .setIncrBy(Content::getPlayCount, playCount)
                    .eq(Content::getId, Long.valueOf(contentId.toString()))
                    .update();
            if (!success) {
                recordPlayCountMetric(MetricNames.CONTENT_PLAY_COUNT_FLUSH_FAILED);
                log.warn("更新内容播放量失败, redisKey={}, 内容id:{}", redisHashKey, contentId);
            }
        }
    }

    private void recordPlayCountMetric(String name) {
        try {
            metrics.counter(name).increment();
        } catch (RuntimeException metricsUnavailable) {
            /* 指标故障不能影响认领和落库。 */
        }
    }

    /**
     * 每周日执行一次, 清理用户已过期的长视频观看记录
     */
    @Scheduled(cron = "0 0 3 * * 0")
    public void cleanUserExpiredVideoRecord() {
        long end = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        ScanOptions options = ScanOptions.scanOptions()
                .match(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + "*")
                .build();
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
     * 每 10 分钟把 MySQL content.play_count / like_count 同步到 ES(推荐打分用)。
     * 以 MySQL 为准; Redis 记录上次扫到的最大 id, 游标滚动, 避免单次全表扫描。
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void flushLikeCountAndPlayCount4ES() {
        roundLock.run(VideoScheduleLocks.ES_COUNT_SYNC, this::syncCountsToEs);
    }

    private void syncCountsToEs() {
        long lastId = readEsSyncCursor();
        List<Content> list = contentService.lambdaQuery()
                .gt(Content::getId, lastId)
                .orderByAsc(Content::getId)
                .list()
                .stream()
                .limit(ES_COUNT_SYNC_PAGE_SIZE)
                .toList();
        if (list.isEmpty()) {
            redisTemplate.opsForValue().set(RedisConstants.SCHEDULE_ES_VIDEO_COUNT_SYNC_CURSOR, "0");
            return;
        }
        // 整页一次批量请求, 不再逐条打 search 服务
        List<ContentDocPort.CountSync> counts = list.stream()
                .map(c -> new ContentDocPort.CountSync(c.getId(),
                        c.getPlayCount() != null ? c.getPlayCount() : 0L,
                        c.getLikeCount() != null ? c.getLikeCount() : 0L))
                .toList();
        try {
            contentDocPort.syncPlayLikeBatch(counts);
        } catch (Exception e) {
            log.warn("批量同步 ES 播放量/点赞量失败, 本页 size={}", counts.size(), e);
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

}

package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.counter.AuthorLikeCountService;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

/**
 * video 域定时任务: 计数落库与对账 / 观看历史清理 / MySQL→ES 计数同步
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleJob {

    private static final int ES_COUNT_SYNC_PAGE_SIZE = 500;
    private static final long PLAYBACK_RESUME_EXPIRE_DAYS = 30L;
    private static final String PLAYBACK_RESUME_TIMESTAMP_PREFIX = "user:playback:resume:timestamp:";
    private static final String PLAYBACK_RESUME_PREFIX = "user:playback:resume:";
    private static final String ES_COUNT_SYNC_CURSOR = "schedule:es:video:count:sync:cursor";

    private final StringRedisTemplate redisTemplate;
    private final IContentService contentService;
    private final ContentDocPort contentDocPort;
    private final CounterFlushService counterFlushService;
    private final ScheduleRoundLock roundLock;
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
     * 每周日执行一次, 清理用户已过期的长视频观看记录
     */
    @Scheduled(cron = "0 0 3 * * 0")
    public void cleanUserExpiredVideoRecord() {
        long end = System.currentTimeMillis() - PLAYBACK_RESUME_EXPIRE_DAYS * 24 * 3600 * 1000;
        ScanOptions options = ScanOptions.scanOptions()
                .match(PLAYBACK_RESUME_TIMESTAMP_PREFIX + "*")
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String zsetKey = cursor.next();
                String userId = zsetKey.split(PLAYBACK_RESUME_TIMESTAMP_PREFIX)[1];
                String hashKey = PLAYBACK_RESUME_PREFIX + userId;
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
            redisTemplate.opsForValue().set(ES_COUNT_SYNC_CURSOR, "0");
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
            redisTemplate.opsForValue().set(ES_COUNT_SYNC_CURSOR, "0");
        } else {
            redisTemplate.opsForValue().set(ES_COUNT_SYNC_CURSOR, String.valueOf(maxId));
        }
    }

    private long readEsSyncCursor() {
        String v = redisTemplate.opsForValue().get(ES_COUNT_SYNC_CURSOR);
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

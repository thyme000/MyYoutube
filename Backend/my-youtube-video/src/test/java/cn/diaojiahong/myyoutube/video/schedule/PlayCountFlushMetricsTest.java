package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.counter.AuthorLikeCountService;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayCountFlushMetricsTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final IContentService contents = mock(IContentService.class);
    @SuppressWarnings("unchecked")
    private final LambdaUpdateChainWrapper<Content> updates = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
    private final ScheduleRoundLock roundLock = mock(ScheduleRoundLock.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final String shard = RedisConstants.videoPlayCountShardKey(0);

    @BeforeEach
    void setup() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(anyString())).thenReturn(Map.of());
        when(contents.lambdaUpdate()).thenReturn(updates);
        doReturn(updates).when(updates).setIncrBy(any(), any(Number.class));
        doReturn(updates).when(updates).eq(any(), any());
        when(updates.update()).thenReturn(true);
        doAnswer(call -> {
            Runnable round = call.getArgument(1);
            round.run();
            return null;
        }).when(roundLock).run(anyString(), any(Runnable.class));
    }

    @AfterEach
    void close() {
        metrics.close();
    }

    @Test
    void emptyRoundRecordsDurationWithoutCountingUnclaimedRows() {
        job(metrics).updateVideoPlayCount();

        assertEquals(1, metrics.get(MetricNames.CONTENT_PLAY_COUNT_FLUSH_DURATION).timer().count());
        assertEquals(0, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_PROCESSED));
        assertEquals(0, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_FAILED));
        verifyNoInteractions(contents);
    }

    @Test
    void onlyNonemptyClaimsCountAsProcessed() {
        when(hashes.entries(shard)).thenReturn(Map.of("42", "5", "43", "0"));
        when(redis.execute(any(RedisScript.class), eq(List.of(shard)), eq("42"))).thenReturn("5");

        job(metrics).updateVideoPlayCount();

        assertEquals(1, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_PROCESSED));
        assertEquals(0, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_FAILED));
        verify(updates).update();
    }

    @Test
    void falseUpdateCountsOneFailureAndKeepsProcessingTheRound() {
        when(hashes.entries(shard)).thenReturn(Map.of("42", "5", "43", "7"));
        when(redis.execute(any(RedisScript.class), eq(List.of(shard)), anyString())).thenReturn("5");
        when(updates.update()).thenReturn(false, true);

        job(metrics).updateVideoPlayCount();

        assertEquals(2, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_PROCESSED));
        assertEquals(1, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_FAILED));
        assertEquals(1, metrics.get(MetricNames.CONTENT_PLAY_COUNT_FLUSH_DURATION).timer().count());
        verify(updates, times(2)).update();
    }

    @Test
    void databaseExceptionIsCountedOnceAndRemainsVisibleToTheCaller() {
        claimOneRow();
        var failure = new IllegalStateException("database unavailable");
        when(updates.update()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> job(metrics).updateVideoPlayCount()));

        assertEquals(1, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_PROCESSED));
        assertEquals(1, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_FAILED));
        assertEquals(1, metrics.get(MetricNames.CONTENT_PLAY_COUNT_FLUSH_DURATION).timer().count());
    }

    @Test
    void scanFailureRecordsTheRoundFailureAndDuration() {
        var failure = new IllegalStateException("redis unavailable");
        when(hashes.entries(shard)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> job(metrics).updateVideoPlayCount()));

        assertEquals(0, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_PROCESSED));
        assertEquals(1, count(MetricNames.CONTENT_PLAY_COUNT_FLUSH_FAILED));
        assertEquals(1, metrics.get(MetricNames.CONTENT_PLAY_COUNT_FLUSH_DURATION).timer().count());
    }

    @Test
    void skippedLockDoesNotLookLikeAnExecutedRound() {
        doNothing().when(roundLock).run(anyString(), any(Runnable.class));

        job(metrics).updateVideoPlayCount();

        assertNull(metrics.find(MetricNames.CONTENT_PLAY_COUNT_FLUSH_DURATION).timer());
        verifyNoInteractions(redis, contents);
    }

    @Test
    void brokenMetricsCannotFailASuccessfulFlushOrHideTheOriginalFailure() {
        claimOneRow();
        MeterRegistry unavailable = mock(MeterRegistry.class);
        when(unavailable.counter(anyString())).thenThrow(new IllegalStateException("metrics unavailable"));
        when(unavailable.timer(anyString())).thenThrow(new IllegalStateException("metrics unavailable"));

        assertDoesNotThrow(() -> job(unavailable).updateVideoPlayCount());

        var failure = new IllegalStateException("database unavailable");
        when(updates.update()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> job(unavailable).updateVideoPlayCount()));
    }

    private void claimOneRow() {
        when(hashes.entries(shard)).thenReturn(Map.of("42", "5"));
        when(redis.execute(any(RedisScript.class), eq(List.of(shard)), eq("42"))).thenReturn("5");
    }

    private ScheduleJob job(MeterRegistry registry) {
        return new ScheduleJob(redis, contents, mock(ContentDocPort.class), mock(CounterFlushService.class),
                roundLock, registry, mock(AuthorLikeCountService.class));
    }

    private double count(String name) {
        Counter counter = metrics.find(name).counter();
        return counter == null ? 0 : counter.count();
    }
}

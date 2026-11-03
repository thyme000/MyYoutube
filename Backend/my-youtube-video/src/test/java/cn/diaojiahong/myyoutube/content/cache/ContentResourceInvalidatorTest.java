package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentResourceInvalidatorTest {
    private static final ContentResourceRegion REGION = ContentResourceRegion.CONTENT_STATE;
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ContentResourceCacheProperties properties = new ContentResourceCacheProperties();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ContentResourceCache cache = spy(new ContentResourceCache(
            redis, mock(RedissonClient.class), properties, metrics));
    private final ContentResourceInvalidator invalidator = new ContentResourceInvalidator(
            cache, scheduler, properties, metrics);

    @AfterEach
    void cleanup() {
        metrics.close();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void waitsForCommitAndSchedulesASecondDeletion() {
        TransactionSynchronizationManager.initSynchronization();
        invalidator.evictAfterCommit(REGION, "1");
        verifyNoInteractions(cache, scheduler);

        Instant before = Instant.now();
        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        callbacks.forEach(TransactionSynchronization::afterCommit);
        verify(cache).evict(REGION, "1");

        ArgumentCaptor<Runnable> delayed = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(delayed.capture(), when.capture());
        assertFalse(when.getValue().isBefore(before.plusMillis(properties.getDoubleDeleteDelayMillis())));
        assertFalse(when.getValue().isAfter(Instant.now().plusMillis(properties.getDoubleDeleteDelayMillis())));

        delayed.getValue().run();
        verify(cache, times(2)).evict(REGION, "1");
    }

    @Test
    void rollbackDoesNotDeleteOrScheduleAnything() {
        TransactionSynchronizationManager.initSynchronization();
        invalidator.evictAfterCommit(REGION, "1");
        TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(cache, scheduler);
    }

    @Test
    void delayedDeletionRemovesAStaleRefillAfterTheFirstDeletion() {
        var values = new HashMap<String, String>();
        values.put("1", "old");
        when(redis.delete(REGION.key("1"))).thenAnswer(call -> {
            values.remove("1");
            return true;
        });

        invalidator.evictAfterCommit(REGION, "1");
        assertFalse(values.containsKey("1"));
        values.put("1", "stale in-flight load");
        ArgumentCaptor<Runnable> delayed = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(delayed.capture(), any(Instant.class));
        delayed.getValue().run();
        assertFalse(values.containsKey("1"));
    }

    @Test
    void failedFirstDeletionStillSchedulesTheSecondAttempt() {
        when(redis.delete(REGION.key("1"))).thenThrow(new IllegalStateException("Redis unavailable")).thenReturn(true);
        assertDoesNotThrow(() -> invalidator.evictAfterCommit(REGION, "1"));
        ArgumentCaptor<Runnable> delayed = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(delayed.capture(), any(Instant.class));
        delayed.getValue().run();
        verify(redis, times(2)).delete(REGION.key("1"));
        assertEquals(1, metrics.get(MetricNames.CONTENT_CACHE_EVICT_FAILED).counter().count());
    }

    @Test
    void failedDeletionDoesNotFailTheCommittedOperation() {
        when(redis.delete(REGION.key("1"))).thenThrow(new IllegalStateException("Redis unavailable"));
        assertDoesNotThrow(() -> invalidator.evictAfterCommit(REGION, "1"));
        ArgumentCaptor<Runnable> delayed = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(delayed.capture(), any(Instant.class));
        assertDoesNotThrow(() -> delayed.getValue().run());
        verify(cache, times(2)).evict(REGION, "1");
        assertEquals(2, metrics.get(MetricNames.CONTENT_CACHE_EVICT_FAILED).counter().count());
    }

    @Test
    void schedulingFailuresDoNotFailTheWriteOrLeakPendingSlots() {
        for (int i = 0; i < 1023; i++) invalidator.evictAfterCommit(REGION, "1");
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new RejectedExecutionException("stopping")).thenReturn(null);
        assertDoesNotThrow(() -> invalidator.evictAfterCommit(REGION, "1"));
        invalidator.evictAfterCommit(REGION, "1");
        verify(cache, times(1025)).evict(REGION, "1");
        verify(scheduler, times(1025)).schedule(any(Runnable.class), any(Instant.class));
        assertEquals(1, metrics.get(MetricNames.CONTENT_CACHE_DELAYED_EVICT_DROPPED)
                .tag("reason", "SCHEDULING_FAILED").counter().count());
    }

    @Test
    void fullQueueKeepsTheImmediateDeletionAndRecoversAfterCompletion() {
        for (int i = 0; i < 1025; i++) invalidator.evictAfterCommit(REGION, "1");
        ArgumentCaptor<Runnable> delayed = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(1024)).schedule(delayed.capture(), any(Instant.class));
        verify(cache, times(1025)).evict(REGION, "1");
        assertEquals(1, metrics.get(MetricNames.CONTENT_CACHE_DELAYED_EVICT_DROPPED)
                .tag("reason", "QUEUE_FULL").counter().count());

        when(redis.delete(REGION.key("1"))).thenThrow(new IllegalStateException("Redis unavailable"));
        delayed.getAllValues().get(0).run();
        clearInvocations(scheduler);
        invalidator.evictAfterCommit(REGION, "1");
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void droppedMetricsCannotFailTheWriteOrLeakPendingSlots() {
        MeterRegistry unavailable = mock(MeterRegistry.class);
        when(unavailable.counter(MetricNames.CONTENT_CACHE_DELAYED_EVICT_DROPPED, "reason", "SCHEDULING_FAILED"))
                .thenThrow(new IllegalStateException("metrics unavailable"));
        var measuredInvalidator = new ContentResourceInvalidator(cache, scheduler, properties, unavailable);
        for (int i = 0; i < 1023; i++) measuredInvalidator.evictAfterCommit(REGION, "1");
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new RejectedExecutionException("stopping")).thenReturn(null);

        assertDoesNotThrow(() -> measuredInvalidator.evictAfterCommit(REGION, "1"));
        measuredInvalidator.evictAfterCommit(REGION, "1");
        verify(scheduler, times(1025)).schedule(any(Runnable.class), any(Instant.class));
        verify(unavailable).counter(MetricNames.CONTENT_CACHE_DELAYED_EVICT_DROPPED, "reason", "SCHEDULING_FAILED");
    }
}

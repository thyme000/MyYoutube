package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceBusyException;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceLockUnavailableException;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader.FillPolicy;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceRegion;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceWarmupException;
import cn.diaojiahong.myyoutube.content.cache.WarmupResource;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceWarmupProperties;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentResourceWarmupTest {
    private final Executor executor = mock(Executor.class);
    private final Executor loaderExecutor = Runnable::run;
    private final ContentResourceWarmupProperties properties = new ContentResourceWarmupProperties();
    private final ContentResourceWarmupLoader loader = mock(ContentResourceWarmupLoader.class);
    private final ContentMediaResources media = mock(ContentMediaResources.class);
    private final ContentUnderstandingResources understanding = mock(ContentUnderstandingResources.class);
    private final ContentResourceCache cache = mock(ContentResourceCache.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final List<Runnable> tasks = new ArrayList<>();
    private final Content content = new Content().setId(1L).setAuthorId(9L).setPlayCount(0L);

    @BeforeEach
    void setup() {
        doAnswer(call -> {
            tasks.add(call.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(loader.loadIfEligible(1L)).thenReturn(content);
    }

    @AfterEach
    void close() {
        metrics.close();
    }

    @Test
    void submissionDefersReadsAndUsesStandardPolicyEvenWhenPlayCountIsZero() {
        warmup(metrics).submit(1L);

        assertEquals(1, tasks.size());
        verifyNoInteractions(loader, media, understanding);
        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SUBMITTED));
        tasks.get(0).run();

        verify(loader).loadIfEligible(1L);
        verify(media).warmupResource(content, FillPolicy.STANDARD);
        verify(understanding).warmupResource(1L, FillPolicy.STANDARD);
        verifyNoMoreInteractions(media, understanding);
        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
    }

    @Test
    void disabledWarmupNeverEnqueuesOrReads() {
        properties.setEnabled(false);

        warmup(metrics).submit(1L);

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "DISABLED"));
        verifyNoInteractions(executor, loader, media, understanding);
    }

    @Test
    void rejectionIsObservableWithoutFailingThePublisher() {
        doThrow(new RejectedExecutionException("queue full")).when(executor).execute(any(Runnable.class));

        assertDoesNotThrow(() -> warmup(metrics).submit(1L));

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "EXECUTOR_REJECTED"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SUBMITTED));
        verifyNoInteractions(loader, media, understanding);
    }

    @Test
    void unavailableExecutorAlsoCannotFailThePublisher() {
        doThrow(new IllegalStateException("executor stopped")).when(executor).execute(any(Runnable.class));

        assertDoesNotThrow(() -> warmup(metrics).submit(1L));

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        verifyNoInteractions(loader, media, understanding);
    }

    @Test
    void authorBelowTheThresholdDoesNotLoadArtifacts() {
        when(loader.loadIfEligible(1L)).thenReturn(null);
        warmup(metrics).submit(1L);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "BELOW_FAN_THRESHOLD"));
        verifyNoInteractions(media, understanding);
    }

    @Test
    void contentThatBecomesInaccessibleBeforeExecutionIsSkipped() {
        warmup(metrics).submit(1L);
        when(loader.loadIfEligible(1L)).thenThrow(new BizException(ErrorCode.NOT_FOUND));

        assertDoesNotThrow(() -> tasks.get(0).run());

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "NOT_READABLE"));
        verifyNoInteractions(media, understanding);
    }

    @Test
    void artifactFailureIsContainedAndDoesNotReportCompletion() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new IllegalStateException("database unavailable"));
        warmup(metrics).submit(1L);

        assertDoesNotThrow(() -> tasks.get(0).run());

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
        verify(understanding, never()).get(any(), any(), any());
    }

    @Test
    void busyResourceSkipsTheTaskWithoutFailureOrFurtherLoads() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new ContentResourceBusyException());
        warmup(metrics).submit(1L);

        assertDoesNotThrow(() -> tasks.get(0).run());

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "LOCKED"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
        verify(understanding, never()).get(any(), any(), any());
    }

    @Test
    void rejectedLoaderIsSkippedWithoutFailingThePublisher() {
        failBatch(ContentResourceRegion.UNDERSTANDING, new RejectedExecutionException("loader queue full"));
        warmup(metrics).submit(1L);

        assertDoesNotThrow(() -> tasks.get(0).run());

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "EXECUTOR_REJECTED"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
    }

    @Test
    void redisLockUnavailabilityIsSkippedRatherThanReportedAsALoadFailure() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA,
                new ContentResourceLockUnavailableException(new IllegalStateException("redis offline")));
        warmup(metrics).submit(1L);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "REDIS_UNAVAILABLE"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
    }

    @Test
    void dependencyUnavailabilityIsStillAFailureRatherThanLockContention() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new DependencyException(
                ErrorCode.DEPENDENCY_UNAVAILABLE, "dependency offline", null));
        warmup(metrics).submit(1L);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "LOCKED"));
    }

    @Test
    void notFoundFromAResourceIsAFailureRatherThanInaccessibleContent() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new BizException(ErrorCode.NOT_FOUND, "媒体不存在"));
        warmup(metrics).submit(1L);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "NOT_READABLE"));
    }

    @Test
    void lockContentionDoesNotHideAnotherResourcesUnexpectedFailure() {
        var failure = new ContentResourceWarmupException(ContentResourceRegion.CONTENT_MEDIA, new ContentResourceBusyException());
        failure.addSuppressed(new ContentResourceWarmupException(ContentResourceRegion.UNDERSTANDING,
                new IllegalStateException("database unavailable")));
        doThrow(failure).when(cache).warmupTogether(anyString(), same(loaderExecutor), any(WarmupResource[].class));
        warmup(metrics).submit(1L);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "LOCKED"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
    }

    @Test
    void mediaThatIsNotReadySkipsFurtherLoads() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new BizException(ErrorCode.STATE_CONFLICT));
        warmup(metrics).submit(1L);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "NOT_READY"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        verify(understanding, never()).get(any(), any(), any());
    }

    @Test
    void stateConflictOutsideMediaLoadingIsStillAFailure() {
        failBatch(ContentResourceRegion.UNDERSTANDING, new BizException(ErrorCode.STATE_CONFLICT));
        warmup(metrics).submit(1L);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "NOT_READY"));
    }

    @Test
    void interruptionIsSkippedWithoutClearingTheInterruptFlag() {
        doAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "interrupted", new InterruptedException());
        }).when(cache).warmupTogether(anyString(), same(loaderExecutor), any(WarmupResource[].class));
        warmup(metrics).submit(1L);
        try {
            tasks.get(0).run();

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "INTERRUPTED"));
            assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
            verify(understanding, never()).get(any(), any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void brokenMetricsCannotPreventSubmissionExecutionOrRejectionHandling() {
        MeterRegistry unavailable = mock(MeterRegistry.class);
        when(unavailable.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("metrics offline"));

        assertDoesNotThrow(() -> warmup(unavailable).submit(1L));
        assertDoesNotThrow(() -> tasks.get(0).run());
        verify(media).warmupResource(content, FillPolicy.STANDARD);
        doThrow(new RejectedExecutionException("queue full")).when(executor).execute(any(Runnable.class));
        assertDoesNotThrow(() -> warmup(unavailable).submit(1L));
    }

    private void failBatch(ContentResourceRegion region, RuntimeException cause) {
        doThrow(new ContentResourceWarmupException(region, cause)).when(cache)
                .warmupTogether(anyString(), same(loaderExecutor), any(WarmupResource[].class));
    }

    private ContentResourceWarmup warmup(MeterRegistry registry) {
        return new ContentResourceWarmup(executor, loaderExecutor, properties, loader, media, understanding, cache, registry);
    }

    private double count(String name, String... tags) {
        Counter counter = metrics.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }
}

package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Summary;
import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.common.media.VideoPlayback;
import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceBusyException;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache.WarmupResult;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceLockUnavailableException;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader.FillPolicy;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceRegion;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceWarmupException;
import cn.diaojiahong.myyoutube.content.cache.WarmupResource;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceWarmupProperties;
import cn.diaojiahong.myyoutube.content.domain.ContentUnderstandingResource;
import cn.diaojiahong.myyoutube.content.domain.ContentUnderstandingResource.Snapshot;
import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final Snapshot snapshot = new Snapshot(true, "审核摘要", null, null);

    private final WarmupResource<ContentMediaResource> mediaResource = new WarmupResource<>(
            ContentResourceRegion.CONTENT_MEDIA, "1", () -> null, FillPolicy.STANDARD);
    private final WarmupResource<ContentUnderstandingResource> understandingResource = new WarmupResource<>(
            ContentResourceRegion.UNDERSTANDING, "1", () -> null, FillPolicy.STANDARD);

    @BeforeEach
    void setup() {
        doAnswer(call -> {
            tasks.add(call.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(loader.loadReadable(1L)).thenReturn(content);
        when(loader.aboveFanThreshold(content)).thenReturn(true);
        when(media.warmupResource(content, FillPolicy.STANDARD)).thenReturn(mediaResource);
        when(understanding.warmupResource(1L, snapshot, FillPolicy.STANDARD)).thenReturn(understandingResource);
        when(cache.warmupTogether(same(loaderExecutor), any(WarmupResource[].class)))
                .thenReturn(new WarmupResult(Map.of(), null));
    }

    @AfterEach
    void close() {
        metrics.close();
    }

    @Test
    void submissionDefersReadsAndUsesStandardPolicyEvenWhenPlayCountIsZero() {
        warmup(metrics).submit(1L, snapshot);

        assertEquals(1, tasks.size());
        verifyNoInteractions(loader, media, understanding);
        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SUBMITTED));
        tasks.get(0).run();

        verify(loader).loadReadable(1L);
        verify(media).warmupResource(content, FillPolicy.STANDARD);
        verify(understanding).warmupResource(1L, snapshot, FillPolicy.STANDARD);
        verifyNoMoreInteractions(media, understanding);
        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
    }

    @Test
    void prewarmsAllDistinctAvailableRenditionsAndOneSubtitleInTheSecondBatch() {
        ContentMediaResource loadedMedia = playback("480.m3u8", "720.m3u8", "720.m3u8",
                "1080.m3u8", "source.m3u8", "extra.m3u8", " ", null);
        ContentUnderstandingResource loadedUnderstanding =
                new ContentUnderstandingResource(new Summary("摘要", List.of()), "subtitle.vtt");
        when(cache.warmupTogether(loaderExecutor, mediaResource, understandingResource)).thenReturn(
                new WarmupResult(Map.of(mediaResource.key(), loadedMedia, understandingResource.key(), loadedUnderstanding), null));
        WarmupResource<String> p480 = playlist("480.m3u8");
        WarmupResource<String> p720 = playlist("720.m3u8");
        WarmupResource<String> p1080 = playlist("1080.m3u8");
        WarmupResource<String> source = playlist("source.m3u8");
        WarmupResource<String> extra = playlist("extra.m3u8");
        WarmupResource<String> subtitle = subtitle("subtitle.vtt");

        warmup(metrics).submit(1L, snapshot);
        tasks.get(0).run();

        InOrder calls = inOrder(cache);
        calls.verify(cache).warmupTogether(loaderExecutor, mediaResource, understandingResource);
        calls.verify(cache).warmupTogether(loaderExecutor, p480, p720, p1080, source, extra, subtitle);
        verifyNoMoreInteractions(cache);
        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
    }

    @Test
    void successfulMediaMetadataStillWarmsPlaylistsWhenUnderstandingIsBusy() {
        ContentResourceWarmupException busy =
                new ContentResourceWarmupException(ContentResourceRegion.UNDERSTANDING, new ContentResourceBusyException());
        ContentMediaResource loadedMedia = playback("source.m3u8");
        when(cache.warmupTogether(loaderExecutor, mediaResource, understandingResource)).thenReturn(
                new WarmupResult(Map.of(mediaResource.key(), loadedMedia), busy));
        WarmupResource<String> source = playlist("source.m3u8");

        warmup(metrics).submit(1L, snapshot);
        tasks.get(0).run();

        verify(cache).warmupTogether(loaderExecutor, source);
        verify(media, never()).warmupSubtitle(anyString(), any());
        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "LOCKED"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
    }

    @Test
    void subtitleFailureRemainsVisibleWhenMediaMetadataWasSkipped() {
        ContentResourceWarmupException busy =
                new ContentResourceWarmupException(ContentResourceRegion.CONTENT_MEDIA, new ContentResourceBusyException());
        ContentUnderstandingResource loadedUnderstanding =
                new ContentUnderstandingResource(new Summary("摘要", List.of()), "subtitle.vtt");
        when(cache.warmupTogether(loaderExecutor, mediaResource, understandingResource)).thenReturn(new WarmupResult(
                Map.of(understandingResource.key(), loadedUnderstanding), busy));
        WarmupResource<String> subtitle = subtitle("subtitle.vtt");
        when(cache.warmupTogether(loaderExecutor, subtitle)).thenReturn(new WarmupResult(Map.of(),
                new ContentResourceWarmupException(ContentResourceRegion.SUBTITLE, new IllegalStateException("S3 offline"))));

        warmup(metrics).submit(1L, snapshot);
        tasks.get(0).run();

        verify(cache).warmupTogether(loaderExecutor, subtitle);
        verify(media, never()).warmupPlaylist(anyString(), any());
        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "LOCKED"));
    }

    @Test
    void disabledWarmupNeverEnqueuesOrReads() {
        properties.setEnabled(false);

        warmup(metrics).submit(1L, snapshot);

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "DISABLED"));
        verifyNoInteractions(executor, loader, media, understanding);
    }

    @Test
    void rejectionIsObservableWithoutFailingThePublisher() {
        doThrow(new RejectedExecutionException("queue full")).when(executor).execute(any(Runnable.class));

        assertDoesNotThrow(() -> warmup(metrics).submit(1L, snapshot));

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "EXECUTOR_REJECTED"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SUBMITTED));
        verifyNoInteractions(loader, media, understanding);
    }

    @Test
    void unavailableExecutorAlsoCannotFailThePublisher() {
        doThrow(new IllegalStateException("executor stopped")).when(executor).execute(any(Runnable.class));

        assertDoesNotThrow(() -> warmup(metrics).submit(1L, snapshot));

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        verifyNoInteractions(loader, media, understanding);
    }

    @Test
    void authorBelowTheThresholdDoesNotLoadArtifacts() {
        when(loader.loadReadable(1L)).thenReturn(content);
        when(loader.aboveFanThreshold(any())).thenReturn(false);
        warmup(metrics).submit(1L, snapshot);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "BELOW_FAN_THRESHOLD"));
        verifyNoInteractions(media, understanding);
    }

    @Test
    void contentThatBecomesInaccessibleBeforeExecutionIsSkipped() {
        warmup(metrics).submit(1L, snapshot);
        when(loader.loadReadable(1L)).thenThrow(new BizException(ErrorCode.NOT_FOUND));

        assertDoesNotThrow(() -> tasks.get(0).run());

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "NOT_READABLE"));
        verifyNoInteractions(media, understanding);
    }

    @Test
    void artifactFailureIsContainedAndDoesNotReportCompletion() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new IllegalStateException("database unavailable"));
        warmup(metrics).submit(1L, snapshot);

        assertDoesNotThrow(() -> tasks.get(0).run());

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
        verify(understanding, never()).get(any(), any(), any());
    }

    @Test
    void busyResourceWithoutAvailableMetadataSkipsTheTask() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new ContentResourceBusyException());
        warmup(metrics).submit(1L, snapshot);

        assertDoesNotThrow(() -> tasks.get(0).run());

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "LOCKED"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
        verify(understanding, never()).get(any(), any(), any());
    }

    @Test
    void rejectedLoaderIsSkippedWithoutFailingThePublisher() {
        failBatch(ContentResourceRegion.UNDERSTANDING, new RejectedExecutionException("loader queue full"));
        warmup(metrics).submit(1L, snapshot);

        assertDoesNotThrow(() -> tasks.get(0).run());

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "EXECUTOR_REJECTED"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
    }

    @Test
    void redisLockUnavailabilityIsSkippedRatherThanReportedAsALoadFailure() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA,
                new ContentResourceLockUnavailableException(new IllegalStateException("redis offline")));
        warmup(metrics).submit(1L, snapshot);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "REDIS_UNAVAILABLE"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
    }

    @Test
    void dependencyUnavailabilityIsStillAFailureRatherThanLockContention() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new DependencyException(
                ErrorCode.DEPENDENCY_UNAVAILABLE, "dependency offline", null));
        warmup(metrics).submit(1L, snapshot);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "LOCKED"));
    }

    @Test
    void notFoundFromAResourceIsAFailureRatherThanInaccessibleContent() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new BizException(ErrorCode.NOT_FOUND, "媒体不存在"));
        warmup(metrics).submit(1L, snapshot);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "NOT_READABLE"));
    }

    @Test
    void lockContentionDoesNotHideAnotherResourcesUnexpectedFailure() {
        var failure = new ContentResourceWarmupException(ContentResourceRegion.CONTENT_MEDIA, new ContentResourceBusyException());
        failure.addSuppressed(new ContentResourceWarmupException(ContentResourceRegion.UNDERSTANDING,
                new IllegalStateException("database unavailable")));
        when(cache.warmupTogether(loaderExecutor, mediaResource, understandingResource))
                .thenReturn(new WarmupResult(Map.of(), failure));
        warmup(metrics).submit(1L, snapshot);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "LOCKED"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED));
    }

    @Test
    void mediaThatIsNotReadySkipsFurtherLoads() {
        failBatch(ContentResourceRegion.CONTENT_MEDIA, new BizException(ErrorCode.STATE_CONFLICT));
        warmup(metrics).submit(1L, snapshot);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "NOT_READY"));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        verify(understanding, never()).get(any(), any(), any());
    }

    @Test
    void stateConflictOutsideMediaLoadingIsStillAFailure() {
        failBatch(ContentResourceRegion.UNDERSTANDING, new BizException(ErrorCode.STATE_CONFLICT));
        warmup(metrics).submit(1L, snapshot);

        tasks.get(0).run();

        assertEquals(1, count(MetricNames.CONTENT_CACHE_WARMUP_FAILED));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", "NOT_READY"));
    }

    @Test
    void interruptionIsSkippedWithoutClearingTheInterruptFlag() {
        doAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "interrupted", new InterruptedException());
        }).when(cache).warmupTogether(same(loaderExecutor), any(WarmupResource[].class));
        warmup(metrics).submit(1L, snapshot);
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

        assertDoesNotThrow(() -> warmup(unavailable).submit(1L, snapshot));
        assertDoesNotThrow(() -> tasks.get(0).run());
        verify(media).warmupResource(content, FillPolicy.STANDARD);
        doThrow(new RejectedExecutionException("queue full")).when(executor).execute(any(Runnable.class));
        assertDoesNotThrow(() -> warmup(unavailable).submit(1L, snapshot));
    }

    private ContentMediaResource playback(String... keys) {
        List<VideoPlayback.Rendition> renditions = new ArrayList<>();
        for (String key : keys) {
            renditions.add(new VideoPlayback.Rendition(renditions.size(), "media.mp4", key, 1920, 1080, 1, null));
        }
        return new ContentMediaResource(mock(ContentMedia.class), new VideoPlayback(1920, 1080, renditions), "version");
    }

    private WarmupResource<String> playlist(String key) {
        WarmupResource<String> resource = new WarmupResource<>(
                ContentResourceRegion.PLAYLIST, key, () -> "playlist", FillPolicy.STANDARD);
        when(media.warmupPlaylist(key, FillPolicy.STANDARD)).thenReturn(resource);
        return resource;
    }

    private WarmupResource<String> subtitle(String key) {
        WarmupResource<String> resource = new WarmupResource<>(
                ContentResourceRegion.SUBTITLE, key, () -> "subtitle", FillPolicy.STANDARD);
        when(media.warmupSubtitle(key, FillPolicy.STANDARD)).thenReturn(resource);
        return resource;
    }

    private void failBatch(ContentResourceRegion region, RuntimeException cause) {
        when(cache.warmupTogether(loaderExecutor, mediaResource, understandingResource))
                .thenReturn(new WarmupResult(Map.of(), new ContentResourceWarmupException(region, cause)));
    }

    private ContentResourceWarmup warmup(MeterRegistry registry) {
        return new ContentResourceWarmup(executor, loaderExecutor, properties, loader, media, understanding, cache, registry);
    }

    private double count(String name, String... tags) {
        Counter counter = metrics.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }
}

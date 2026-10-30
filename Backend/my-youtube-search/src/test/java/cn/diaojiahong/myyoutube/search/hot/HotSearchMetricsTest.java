package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static cn.diaojiahong.myyoutube.search.hot.HotSearchMetrics.Operation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HotSearchMetricsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    final HotSearchMetrics metrics = new HotSearchMetrics(registry);

    final HotSearchStore store = mock(HotSearchStore.class);

    final HotSearchProperties properties = new HotSearchProperties();

    final PipelineObservationProperties observation = new PipelineObservationProperties();

    final AtomicLong now = new AtomicLong(Instant.parse("2026-10-21T20:00:00Z").toEpochMilli());

    Clock clock() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(call -> now.get());
        when(clock.instant()).thenAnswer(call -> Instant.ofEpochMilli(now.get()));
        return clock;
    }

    @Test
    void observationFailurePreservesBothBusinessResultAndOriginalException() {
        var unavailable = new HotSearchMetrics(mock(MeterRegistry.class));
        var error = new IllegalStateException("private search text");
        assertEquals("result", unavailable.call(HISTORY_WRITE, () -> "result"));
        assertSame(error, assertThrows(IllegalStateException.class, () -> unavailable.run(SUMMARY, () -> {
            throw error;
        })));
        assertDoesNotThrow(() -> unavailable.record(HotSearchMetrics.RecordResult.ACCEPTED));
    }

    @Test
    void partialModelsAndOperationFailuresRemainDistinctWithoutRecordingSensitiveLabels() {
        metrics.modelItems(EMBEDDING, 5, 3);
        var error = new IllegalStateException("private search text");
        assertSame(error, assertThrows(IllegalStateException.class, () -> metrics.run(EMBEDDING, () -> {
            throw error;
        })));
        assertEquals(2, registry.get(MetricNames.HOT_SEARCH_MODEL_ITEMS)
                .tags("operation", "embedding", "outcome", "unassigned")
                .counter()
                .count());
        assertEquals(1, registry.get(MetricNames.HOT_SEARCH_DURATION)
                .tags("operation", "embedding", "outcome", "failure")
                .timer()
                .count());
        registry.getMeters().forEach(meter -> {
            assertFalse(meter.getId().toString().contains("private"));
            assertTrue(meter.getId()
                    .getTags()
                    .stream()
                    .allMatch(tag -> List.of("operation", "outcome", "kind", "le").contains(tag.getKey())));
        });
    }

    @Test
    void prometheusExportKeepsUniformLabelKeysAndNoTrafficIsNotAPublishedEmptySnapshot() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var exported = new HotSearchMetrics(prometheus);
            assertTrue(Double.isNaN(prometheus.get(MetricNames.HOT_SEARCH_LAST_PUBLISH_SECONDS).gauge().value()));
            exported.run(PUBLISH, () -> {
            });
            exported.published(0, 0, now.get());
            exported.read(HotSearchMetrics.ReadKind.HOT, HotSearchMetrics.ReadResult.MISS);
            String text = prometheus.scrape();
            assertTrue(text.contains("myyoutube_hot_search_duration_seconds_bucket"));
            assertTrue(text.contains("myyoutube_hot_search_cache_reads_total"));
            assertFalse(text.contains("NaN"));
            assertEquals(0, prometheus.get(MetricNames.HOT_SEARCH_DISPLAYED).gauge().value());
        } finally {
            prometheus.close();
        }
    }

    @Test
    void stockSamplingIsOffByDefaultAndNeverQueriesRedisDuringGaugeReads() {
        var sampler = new HotSearchMetricsSampler(store, properties, observation, registry, clock());
        sampler.sample();
        registry.getMeters().stream().filter(meter -> meter instanceof io.micrometer.core.instrument.Gauge)
                .forEach(meter -> ((io.micrometer.core.instrument.Gauge) meter).value());
        verifyNoInteractions(store);
        assertEquals(0, gauge(MetricNames.PIPELINE_METRICS_SAMPLE_ENABLED));
        assertTrue(Double.isNaN(gauge(MetricNames.HOT_SEARCH_PENDING)));
    }

    @Test
    void failedSamplesRetainDataButBecomeUnhealthyAndAgeInsteadOfReportingZero() {
        observation.getMonitoring().setEnabled(true);
        var sampler = new HotSearchMetricsSampler(store, properties, observation, registry, clock());
        LocalDate closed = LocalDate.of(2026, 10, 20);
        when(store.stats(now.get())).thenReturn(new HotSearchStore.Stats(
                5,
                now.get() - 60_000,
                1,
                closed,
                closed,
                5000));
        sampler.sample();
        assertEquals(5, gauge(MetricNames.HOT_SEARCH_PENDING));
        assertEquals(60, gauge(MetricNames.HOT_SEARCH_PENDING_OLDEST_SECONDS));
        assertEquals(1, gauge(MetricNames.HOT_SEARCH_ARCHIVE_LAG_DAYS));
        assertEquals(1, gauge(MetricNames.PIPELINE_METRICS_SAMPLE_SUCCESS));
        now.addAndGet(120_000);
        when(store.stats(now.get())).thenThrow(new IllegalStateException("redis unavailable"));
        sampler.sample();
        assertEquals(5, gauge(MetricNames.HOT_SEARCH_PENDING));
        assertEquals(0, gauge(MetricNames.PIPELINE_METRICS_SAMPLE_SUCCESS));
        assertEquals(120, gauge(MetricNames.PIPELINE_METRICS_SAMPLE_AGE_SECONDS));
        verify(store, times(2)).stats(anyLong());
    }

    @Test
    void archiveLagChangesAtFourAndAValidEmptySnapshotIsDifferentFromMissingOne() {
        observation.getMonitoring().setEnabled(true);
        now.set(Instant.parse("2026-10-21T19:59:00Z").toEpochMilli());
        var sampler = new HotSearchMetricsSampler(store, properties, observation, registry, clock());
        when(store.stats(anyLong())).thenReturn(new HotSearchStore.Stats(
                0,
                null,
                0,
                null,
                LocalDate.of(2026, 10, 20),
                900_000));
        sampler.sample();
        assertEquals(0, gauge(MetricNames.HOT_SEARCH_ARCHIVE_LAG_DAYS));
        assertEquals(0, gauge(MetricNames.HOT_SEARCH_PENDING_OLDEST_SECONDS));
        assertEquals(900, gauge(MetricNames.HOT_SEARCH_SNAPSHOT_TTL_SECONDS));
        now.addAndGet(60_000);
        assertEquals(1, gauge(MetricNames.HOT_SEARCH_ARCHIVE_LAG_DAYS));
        properties.setEnabled(false);
        sampler.sample();
        verify(store, times(1)).stats(anyLong());
        assertEquals(0, gauge(MetricNames.PIPELINE_METRICS_SAMPLE_ENABLED));
    }

    double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}

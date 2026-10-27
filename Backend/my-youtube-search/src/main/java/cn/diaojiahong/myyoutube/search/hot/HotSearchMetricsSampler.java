package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.monitoring.SampleHealth;
import cn.diaojiahong.myyoutube.common.observability.PipelineObservationProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
@Slf4j
public class HotSearchMetricsSampler {
    private final HotSearchStore store;
    private final HotSearchProperties properties;
    private final PipelineObservationProperties observation;
    private final Clock clock;
    private final SampleHealth health;
    private volatile HotSearchStore.Stats snapshot;

    public HotSearchMetricsSampler(HotSearchStore store, HotSearchProperties properties,
                                  PipelineObservationProperties observation, MeterRegistry registry, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.observation = observation;
        this.clock = clock;
        health = new SampleHealth(registry, clock, "hot_search", this::enabled);
        Gauge.builder(MetricNames.HOT_SEARCH_ENABLED, properties, p -> p.isEnabled() ? 1 : 0).register(registry);
        Gauge.builder(MetricNames.HOT_SEARCH_PENDING, this, value -> value.snapshot == null ? Double.NaN : value.snapshot.pending()).register(registry);
        Gauge.builder(MetricNames.HOT_SEARCH_PENDING_OLDEST_SECONDS, this, value -> value.oldestPending()).register(registry);
        Gauge.builder(MetricNames.HOT_SEARCH_SNAPSHOT_TTL_SECONDS, this, value -> value.snapshot == null ? Double.NaN : Math.max(0, value.snapshot.snapshotTtlMillis()) / 1000d).register(registry);
        Gauge.builder(MetricNames.HOT_SEARCH_SUMMARY_PENDING_DAYS, this, value -> value.snapshot == null ? Double.NaN : value.snapshot.summaryDays()).register(registry);
        Gauge.builder(MetricNames.HOT_SEARCH_SUMMARY_OLDEST_SECONDS, this, value -> value.oldestSummary()).register(registry);
        Gauge.builder(MetricNames.HOT_SEARCH_ARCHIVE_INITIALIZED, this, value -> value.snapshot == null ? Double.NaN : value.snapshot.closedThrough() == null ? 0 : 1).register(registry);
        Gauge.builder(MetricNames.HOT_SEARCH_ARCHIVE_LAG_DAYS, this, value -> value.archiveLag()).register(registry);
    }

    private boolean enabled() { return properties.isEnabled() && observation.getMonitoring().isEnabled(); }

    @Scheduled(fixedDelayString = "${my-youtube.observability.monitoring.interval-ms:30000}",
            initialDelayString = "${my-youtube.observability.monitoring.interval-ms:30000}")
    public void sample() {
        if (!enabled()) return;
        try {
            snapshot = store.stats(clock.millis());
            health.success();
        } catch (RuntimeException error) {
            health.failure();
            log.warn("Hot search metrics retain previous sample errorType={}", error.getClass().getSimpleName());
        }
    }

    private double oldestPending() {
        var value = snapshot;
        return value == null ? Double.NaN : value.pending() == 0 ? 0 : value.oldestPendingMillis() == null
                ? Double.NaN : Math.max(0, (clock.millis() - value.oldestPendingMillis()) / 1000d);
    }

    private double oldestSummary() {
        var value = snapshot;
        if (value == null) return Double.NaN;
        if (value.oldestSummaryDate() == null) return 0;
        long due = value.oldestSummaryDate().plusDays(1).atTime(4, 0).atZone(HotSearchStore.STAT_ZONE).toInstant().toEpochMilli();
        return Math.max(0, (clock.millis() - due) / 1000d);
    }

    private double archiveLag() {
        var value = snapshot;
        if (value == null || value.closedThrough() == null) return Double.NaN;
        var now = clock.instant().atZone(HotSearchStore.STAT_ZONE);
        LocalDate due = now.toLocalDate().minusDays(now.getHour() >= 4 ? 1 : 2);
        return Math.max(0, ChronoUnit.DAYS.between(value.closedThrough(), due));
    }
}

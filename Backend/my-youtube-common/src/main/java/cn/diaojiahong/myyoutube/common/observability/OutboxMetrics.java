package cn.diaojiahong.myyoutube.common.observability;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.monitoring.SampleHealth;
import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.*;
import java.time.*;
import java.time.Clock;
import java.util.*;

public final class OutboxMetrics {
    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final JdbcTemplate jdbc;
    private final PipelineObservationProperties properties;
    private final Clock clock;
    private final SampleHealth health;

    private volatile Map<String, Stock> snapshot = Map.of();

    record Stock(long count, boolean capped, Instant oldest) {
    }

    public OutboxMetrics(
            JdbcTemplate jdbc,
            PipelineObservationProperties properties,
            Clock clock,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        health = new SampleHealth(registry, clock, "outbox", () -> properties.getMonitoring().isEnabled());
        Gauge.builder(
                        MetricNames.PIPELINE_OUTBOX_MONITORING_ENABLED,
                        properties,
                        p -> p.getMonitoring().isEnabled() ? 1 : 0)
                .register(registry);

        for (String status : List.of("PENDING", "SENDING", "QUARANTINED")) {
            Gauge.builder(
                    MetricNames.PIPELINE_OUTBOX_BACKLOG,
                    this,
                    m -> m.snapshot.containsKey(status) ? m.snapshot.get(status).count() : Double.NaN)
                    .tag("status", status).register(registry);
            Gauge.builder(MetricNames.PIPELINE_OUTBOX_BACKLOG_CAPPED, this, m -> m.snapshot.containsKey(status)
                    ? (m.snapshot.get(status).capped() ? 1 : 0) : Double.NaN).tag("status", status).register(registry);
            if (!status.equals("QUARANTINED")) {
                Gauge.builder(MetricNames.PIPELINE_OUTBOX_OLDEST_SECONDS, this, m -> {
                    Stock stock = m.snapshot.get(status);
                    return stock == null ? Double.NaN
                            : stock.oldest() == null ? 0
                            : Math.max(0, Duration.between(stock.oldest(), clock.instant()).toMillis() / 1000d);
                }).tag("status", status).register(registry);
            }
        }
    }

    @Scheduled(
            fixedDelayString = "${my-youtube.observability.monitoring.interval-ms:30000}",
            initialDelayString = "${my-youtube.observability.monitoring.interval-ms:30000}")
    public void sample() {
        if (!properties.getMonitoring().isEnabled()) {
            return;
        }

        try {
            int limit = properties.getMonitoring().getPageLimit();
            Map<String, Stock> fresh = new LinkedHashMap<>();
            long quarantined = 0;
            for (String status : List.of("PENDING", "SENDING")) {
                long count = count(status, "IS NULL", limit);
                List<Instant> oldest = jdbc.query("SELECT created_at FROM pipeline_event_outbox WHERE status=? AND quarantine_reason IS NULL ORDER BY created_at,event_id LIMIT 1",
                        (rs, row) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), status);
                fresh.put(
                        status,
                        new Stock(Math.min(count, limit), count > limit, oldest.isEmpty() ? null : oldest.get(0)));
            }

            for (String status : List.of("PENDING", "SENDING", "SENT")) {
                quarantined += count(status, "IS NOT NULL", limit);
            }
            fresh.put("QUARANTINED", new Stock(Math.min(quarantined, limit), quarantined > limit, null));

            snapshot = Map.copyOf(fresh);
            health.success();
        } catch (Exception error) {
            health.failure();
            log.warn("Outbox metrics retain previous sample errorType={}", error.getClass().getSimpleName());
        }
    }

    private long count(String status, String quarantine, int limit) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT event_id FROM pipeline_event_outbox WHERE status=? AND quarantine_reason "
                + quarantine + " LIMIT ?) bounded_rows", Long.class, status, limit + 1));
    }
}

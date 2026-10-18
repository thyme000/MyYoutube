package cn.diaojiahong.myyoutube.transcode.schedule;

import cn.diaojiahong.myyoutube.common.monitoring.SampleHealth;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.*;
import java.time.*;
import java.time.Clock;
import java.util.*;

@Component
public class PipelineMetrics {
    private static final Logger log = LoggerFactory.getLogger(PipelineMetrics.class);
    private final MediaTaskMapper tasks;
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final SampleHealth health;
    private final boolean oldestEnabled;
    private final Set<List<String>> registered = new HashSet<>();
    private volatile Map<List<String>, Long> stock;
    private volatile Instant oldestReady, oldestDelivery;
    private volatile long inactive;
    private volatile boolean candidatesCapped;
    public PipelineMetrics(MediaTaskMapper tasks, JdbcTemplate jdbc, MeterRegistry metrics, @org.springframework.beans.factory.annotation.Value("${my-youtube.observability.monitoring.enabled:false}") boolean oldestEnabled) {
        this.tasks = tasks; this.jdbc = jdbc; this.metrics = metrics;
        this.oldestEnabled = oldestEnabled;
        health = new SampleHealth(metrics, Clock.systemUTC(), "tasks");
        Gauge.builder("media.pipeline.inactive.lowerbound", this, m -> !m.oldestEnabled || m.stock == null ? Double.NaN : m.inactive).register(metrics);
        Gauge.builder("media.pipeline.inactive.capped", this, m -> !m.oldestEnabled || m.stock == null ? Double.NaN : m.candidatesCapped ? 1 : 0).register(metrics);
        Gauge.builder("media.ready.oldest.seconds", this, m -> m.age(m.oldestReady)).register(metrics);
        Gauge.builder("media.result.delivery.oldest.seconds", this, m -> m.age(m.oldestDelivery)).register(metrics);
    }
    private double age(Instant oldest) {
        return !oldestEnabled || stock == null ? Double.NaN : oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toMillis() / 1000d);
    }
    @Scheduled(fixedDelay=30000, initialDelay=30000)
    public void sample() {
        try {
            Map<List<String>, Long> fresh = new HashMap<>();
            for (var row : tasks.backlog()) fresh.put(List.of(row.get("phase").toString(), row.get("task_type").toString(), row.get("status").toString()),
                    ((Number) row.get("total")).longValue());
            Instant ready = oldestEnabled ? oldest("SELECT ready_at FROM media_task WHERE status='READY' AND ready_at IS NOT NULL ORDER BY ready_at,id LIMIT 1") : null;
            Instant delivery = oldestEnabled ? oldest("SELECT completed_at FROM media_pipeline WHERE status='COMPLETED' AND received=0 AND completed_at IS NOT NULL ORDER BY completed_at,id LIMIT 1") : null;
            long candidates = 0, stalled = 0;
            if (oldestEnabled) {
                candidates = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT id FROM media_pipeline WHERE status='PROCESSING' ORDER BY id LIMIT 2001) candidates", Long.class));
                stalled = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT id FROM media_pipeline WHERE status='PROCESSING' ORDER BY id LIMIT 2000) candidates WHERE NOT EXISTS (SELECT 1 FROM media_task t WHERE t.pipeline_id=candidates.id AND t.phase='CONTENT' AND t.status IN ('READY','RUNNING','RETRY_WAIT'))", Long.class));
            }
            for (var key : fresh.keySet()) if (registered.add(key)) Gauge.builder("media.tasks", this,
                    m -> m.stock == null ? Double.NaN : m.stock.getOrDefault(key, 0L))
                    .tags("phase", key.get(0), "type", key.get(1), "status", key.get(2)).register(metrics);
            oldestReady = ready; oldestDelivery = delivery; inactive = stalled; candidatesCapped = candidates > 2000; stock = Map.copyOf(fresh); health.success();
        } catch (Exception error) {
            health.failure(); log.warn("Task metrics retain previous sample errorType={}", error.getClass().getSimpleName());
        }
    }
    private Instant oldest(String query) {
        var result = jdbc.query(query, (rs, i) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
        return result.isEmpty() ? null : result.get(0);
    }
}

package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.monitoring.SampleHealth;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;

import java.time.*;
import java.time.Clock;
import java.util.*;

@Component
public class BusinessBacklogMetrics {
    private static final Logger log = LoggerFactory.getLogger(BusinessBacklogMetrics.class);
    private static final List<String> DRAFT_STATES = List.of("UPLOADING", "PROCESSING", "READY", "FAILED");
    private static final List<String> BRANCHES = List.of("count", "search", "notice");
    private static final int LIMIT = 2000;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final SampleHealth health;
    private volatile Snapshot snapshot;
    private record Branch(boolean count, boolean search, boolean notice, Instant created) {}
    private record Snapshot(Map<String, Long> drafts, Map<String, Long> pending, Map<String, Instant> oldest, boolean capped) {}

    public BusinessBacklogMetrics(JdbcTemplate jdbc, MeterRegistry registry,
                                 @Value("${my-youtube.observability.monitoring.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.enabled = enabled;
        health = new SampleHealth(registry, Clock.systemUTC(), "business_backlog", () -> enabled);
        for (String state : DRAFT_STATES) {
            Gauge.builder("myyoutube.business.drafts.lowerbound", this, m -> m.snapshot == null ? Double.NaN : m.snapshot.drafts().get(state))
                    .tag("status", state).register(registry);
            Gauge.builder("myyoutube.business.drafts.capped", this, m -> m.snapshot == null ? Double.NaN : m.snapshot.drafts().get(state) > LIMIT ? 1 : 0)
                    .tag("status", state).register(registry);
        }
        for (String branch : BRANCHES) {
            Gauge.builder("myyoutube.business.publication.pending.lowerbound", this, m -> m.snapshot == null ? Double.NaN : m.snapshot.pending().get(branch))
                    .tag("branch", branch).register(registry);
            Gauge.builder("myyoutube.business.publication.oldest.seconds", this, m -> m.age(branch)).tag("branch", branch).register(registry);
        }
        Gauge.builder("myyoutube.business.publication.capped", this, m -> m.snapshot == null ? Double.NaN : m.snapshot.capped() ? 1 : 0).register(registry);
    }

    @Scheduled(fixedDelayString = "${my-youtube.observability.monitoring.interval-ms:30000}", initialDelay = 30000)
    public void sample() {
        if (!enabled) return;
        try {
            var drafts = new HashMap<String, Long>();
            for (String state : DRAFT_STATES) drafts.put(state, Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT id FROM media_draft WHERE status=? LIMIT 2001) bounded_rows", Long.class, state)));
            var rows = jdbc.query("SELECT user_count_done,search_done,notice_done,TIMESTAMPDIFF(SECOND,create_time,CURRENT_TIMESTAMP) AS age "
                    + "FROM content_publication_event WHERE completed=0 ORDER BY create_time,id LIMIT 2001",
                    (rs, i) -> new Branch(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), Instant.now().minusSeconds(Math.max(0, rs.getLong(4)))));
            var pending = new HashMap<String, Long>();
            var oldest = new HashMap<String, Instant>();
            BRANCHES.forEach(branch -> pending.put(branch, 0L));
            for (var row : rows.subList(0, Math.min(LIMIT, rows.size()))) {
                for (String branch : BRANCHES) {
                    boolean done = switch (branch) { case "count" -> row.count(); case "search" -> row.search(); default -> row.notice(); };
                    if (!done) {
                        pending.merge(branch, 1L, Long::sum);
                        oldest.merge(branch, row.created(), (a, b) -> a.isBefore(b) ? a : b);
                    }
                }
            }
            snapshot = new Snapshot(Map.copyOf(drafts), Map.copyOf(pending), Map.copyOf(oldest), rows.size() > LIMIT);
            health.success();
        } catch (Exception error) {
            health.failure();
            log.warn("Business backlog retains previous sample errorType={}", error.getClass().getSimpleName());
        }
    }

    private double age(String branch) {
        var current = snapshot;
        if (current == null) return Double.NaN;
        var oldest = current.oldest().get(branch);
        if (oldest == null) return current.capped() ? Double.NaN : 0;
        return Math.max(0, Duration.between(oldest, Instant.now()).toMillis() / 1000d);
    }
}

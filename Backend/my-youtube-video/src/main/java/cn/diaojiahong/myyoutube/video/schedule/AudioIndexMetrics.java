package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.monitoring.SampleHealth;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
@Slf4j
public class AudioIndexMetrics {
    private static final int LIMIT = 2000;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final SampleHealth health;
    private volatile Snapshot snapshot;
    private record Snapshot(long pending, boolean capped, Instant oldest) {}

    public AudioIndexMetrics(JdbcTemplate jdbc, MeterRegistry registry,
            @Value("${my-youtube.observability.monitoring.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.enabled = enabled;
        health = new SampleHealth(registry, Clock.systemUTC(), "audio_index", () -> enabled);
        Gauge.builder("myyoutube.audio.index.pending.lowerbound", this, m -> m.snapshot == null ? Double.NaN : m.snapshot.pending()).register(registry);
        Gauge.builder("myyoutube.audio.index.pending.capped", this, m -> m.snapshot == null ? Double.NaN : m.snapshot.capped() ? 1 : 0).register(registry);
        Gauge.builder("myyoutube.audio.index.oldest.seconds", this, AudioIndexMetrics::oldestAge).register(registry);
    }

    private double oldestAge() {
        var current = snapshot;
        return current == null ? Double.NaN : current.oldest() == null ? 0
                : Math.max(0, Duration.between(current.oldest(), Instant.now()).toMillis() / 1000d);
    }

    @Scheduled(fixedDelayString = "${my-youtube.observability.monitoring.interval-ms:30000}", initialDelay = 30000)
    public void sample() {
        if (!enabled) return;
        try {
            var ages = jdbc.query("SELECT TIMESTAMPDIFF(SECOND,index_pending_since,CURRENT_TIMESTAMP) FROM audio "
                    + "WHERE index_pending_since IS NOT NULL AND index_version > indexed_version ORDER BY index_pending_since,id LIMIT 2001",
                    (rs, index) -> Math.max(0, rs.getLong(1)));
            snapshot = new Snapshot(Math.min(LIMIT, ages.size()), ages.size() > LIMIT,
                    ages.isEmpty() ? null : Instant.now().minusSeconds(ages.get(0)));
            health.success();
        } catch (Exception error) {
            health.failure(); log.warn("Audio index metrics retain previous sample reason={}", error.getClass().getSimpleName());
        }
    }
}

package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.video.schedule.AudioIndexMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioIndexMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test void disabledSamplerDoesNotQueryDatabaseOrReportHealthyZero() {
        var jdbc = mock(JdbcTemplate.class);
        new AudioIndexMetrics(jdbc, registry, false).sample();
        verifyNoInteractions(jdbc);
        assertTrue(Double.isNaN(gauge(MetricNames.AUDIO_INDEX_PENDING_LOWERBOUND)));
        assertEquals(0, gauge(MetricNames.PIPELINE_METRICS_SAMPLE_ENABLED));
    }

    @Test void samplesDirtyRowsByFirstPendingTimeAndClearsRecoveredBacklog() {
        var jdbc = database(); var now = databaseTime(jdbc);
        jdbc.update("INSERT INTO audio VALUES(1,2,1,?,?)", now.minusSeconds(660), now.plusSeconds(30));
        jdbc.update("INSERT INTO audio VALUES(2,1,0,?,?)", now.minusSeconds(60), now.plusSeconds(30));
        jdbc.update("INSERT INTO audio VALUES(3,1,1,?,?)", now.minusDays(1), now);
        var metrics = new AudioIndexMetrics(jdbc, registry, true);
        metrics.sample();
        assertEquals(2, gauge(MetricNames.AUDIO_INDEX_PENDING_LOWERBOUND));
        double age = gauge(MetricNames.AUDIO_INDEX_OLDEST_SECONDS);
        assertTrue(age >= 659 && age < 665, "Pending age=" + age);
        assertEquals(1, gauge(MetricNames.PIPELINE_METRICS_SAMPLE_SUCCESS));
        jdbc.update("UPDATE audio SET indexed_version=index_version,index_pending_since=NULL");
        metrics.sample();
        assertEquals(0, gauge(MetricNames.AUDIO_INDEX_PENDING_LOWERBOUND));
        assertEquals(0, gauge(MetricNames.AUDIO_INDEX_OLDEST_SECONDS));
    }

    @Test void failedSampleRetainsPreviousSnapshotAndMarksItUnhealthy() {
        var jdbc = database(); var now = databaseTime(jdbc);
        jdbc.update("INSERT INTO audio VALUES(1,1,0,?,?)", now.minusSeconds(660), now);
        var metrics = new AudioIndexMetrics(jdbc, registry, true);
        metrics.sample(); jdbc.execute("DROP TABLE audio"); metrics.sample();
        assertEquals(1, gauge(MetricNames.AUDIO_INDEX_PENDING_LOWERBOUND));
        assertTrue(gauge(MetricNames.AUDIO_INDEX_OLDEST_SECONDS) >= 659);
        assertEquals(0, gauge(MetricNames.PIPELINE_METRICS_SAMPLE_SUCCESS));
        assertEquals(1, registry.get(MetricNames.PIPELINE_METRICS_SAMPLE_FAILURES).counter().count());
    }

    @Test void largeBacklogUsesBoundedLowerBoundAndExposesCap() {
        var jdbc = database(); var now = databaseTime(jdbc);
        var batch = IntStream.rangeClosed(1, 2001).mapToObj(i -> new Object[]{i, now.minusSeconds(i), now}).toList();
        jdbc.batchUpdate("INSERT INTO audio VALUES(?,1,0,?,?)", batch);
        new AudioIndexMetrics(jdbc, registry, true).sample();
        assertEquals(2000, gauge(MetricNames.AUDIO_INDEX_PENDING_LOWERBOUND));
        assertEquals(1, gauge(MetricNames.AUDIO_INDEX_PENDING_CAPPED));
        assertTrue(gauge(MetricNames.AUDIO_INDEX_OLDEST_SECONDS) >= 2000);
    }

    private double gauge(String name) { return registry.get(name).gauge().value(); }
    private static LocalDateTime databaseTime(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
    }
    private static JdbcTemplate database() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE audio(id BIGINT,index_version BIGINT,indexed_version BIGINT,index_pending_since TIMESTAMP,index_after TIMESTAMP)");
        return jdbc;
    }
}

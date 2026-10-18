package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.video.schedule.ReviewMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewMetricsTest {
    @Test void publicationAgeUsesDatabaseClockForDatabaseCreatedTimestamp() {
        var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:review-metrics;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE content_publication_event(id BIGINT,completed BOOLEAN,create_time TIMESTAMP)");
        var dbNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.time.LocalDateTime.class);
        jdbc.update("INSERT INTO content_publication_event VALUES(1,FALSE,?)", dbNow.minusSeconds(660));
        var rows = mock(ContentUnderstandingMapper.class); var events = mock(ContentPublicationEventMapper.class);
        when(rows.selectCount(any())).thenReturn(0L); when(events.selectCount(any())).thenReturn(1L);
        var registry = new SimpleMeterRegistry(); var sampler = new ReviewMetrics(rows,events,jdbc,registry,true);
        sampler.sample(); double age = registry.get("media.publication.oldest.seconds").gauge().value();
        assertTrue(age >= 659 && age < 665, "Publication age=" + age);
    }
    @Test void partialQueryFailurePreservesCompletePreviousSnapshot() {
        var rows = mock(ContentUnderstandingMapper.class); var events = mock(ContentPublicationEventMapper.class);
        var jdbc = mock(JdbcTemplate.class); var registry = new SimpleMeterRegistry();
        var sampler = new ReviewMetrics(rows, events, jdbc, registry, false);
        when(rows.selectCount(any())).thenReturn(2L,3L,0L,0L); when(events.selectCount(any())).thenReturn(4L).thenThrow(new IllegalStateException());
        sampler.sample(); sampler.sample();
        assertEquals(2, registry.get("media.review.queue").tag("mode","automatic").gauge().value());
        assertEquals(3, registry.get("media.review.queue").tag("mode","manual").gauge().value());
        assertEquals(4, registry.get("media.publication.pending").gauge().value());
        assertEquals(0, registry.get("pipeline.metrics.sample.success").gauge().value());
        verifyNoInteractions(jdbc);
    }
}

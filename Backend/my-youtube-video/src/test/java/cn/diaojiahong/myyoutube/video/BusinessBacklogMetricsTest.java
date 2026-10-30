package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.video.schedule.BusinessBacklogMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class BusinessBacklogMetricsTest {
    JdbcTemplate jdbc;
    SimpleMeterRegistry registry;
    BusinessBacklogMetrics sampler;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""));
        jdbc.execute("CREATE TABLE media_draft(id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE content_publication_event(id BIGINT,completed BOOLEAN,search_done BOOLEAN,notice_done BOOLEAN,audio_count_done BOOLEAN,create_time TIMESTAMP)");
        registry = new SimpleMeterRegistry();
        sampler = new BusinessBacklogMetrics(jdbc, registry, true);
    }

    @Test
    void branchesAreIndependentAndAgeUsesDatabaseClock() {
        jdbc.update("INSERT INTO media_draft VALUES(1,'PROCESSING'),(2,'READY')");
        jdbc.update("INSERT INTO content_publication_event VALUES(1,FALSE,TRUE,FALSE,FALSE,DATEADD('SECOND',-600,CURRENT_TIMESTAMP)),(2,FALSE,FALSE,TRUE,TRUE,CURRENT_TIMESTAMP),(3,TRUE,TRUE,TRUE,TRUE,CURRENT_TIMESTAMP)");
        sampler.sample();
        for (String branch : java.util.List.of("search", "notice", "audio_count")) assertEquals(1, pending(branch));
        double age = registry.get(MetricNames.BUSINESS_PUBLICATION_OLDEST_SECONDS)
                .tag("branch", "notice")
                .gauge()
                .value();
        assertTrue(age >= 599 && age < 605);
        assertEquals(1, registry.get(MetricNames.BUSINESS_DRAFTS_LOWERBOUND).tag("status", "READY").gauge().value());
    }

    @Test
    void partialFailureRetainsWholeSnapshotAndMarksUnhealthy() {
        jdbc.update("INSERT INTO content_publication_event VALUES(1,FALSE,FALSE,TRUE,TRUE,CURRENT_TIMESTAMP)");
        sampler.sample();
        jdbc.update("INSERT INTO media_draft VALUES(1,'READY')");
        jdbc.execute("DROP TABLE content_publication_event");
        sampler.sample();
        assertEquals(1, pending("search"));
        assertEquals(0, registry.get(MetricNames.BUSINESS_DRAFTS_LOWERBOUND).tag("status", "READY").gauge().value());
        assertEquals(0, registry.get(MetricNames.PIPELINE_METRICS_SAMPLE_SUCCESS).gauge().value());
    }

    @Test
    void noSampleAndDisabledAreUnknownRatherThanZero() {
        assertTrue(Double.isNaN(pending("search")));
        var disabled = new SimpleMeterRegistry();
        new BusinessBacklogMetrics(jdbc, disabled, false).sample();
        assertEquals(0, disabled.get(MetricNames.PIPELINE_METRICS_SAMPLE_ENABLED).gauge().value());
        assertTrue(Double.isNaN(disabled.get(MetricNames.BUSINESS_PUBLICATION_PENDING_LOWERBOUND).tag("branch", "search").gauge().value()));
    }

    @Test
    void cappedBranchMissingFromSampleIsUnknownAndCountsAreBounded() {
        jdbc.execute("INSERT INTO content_publication_event SELECT X,FALSE,FALSE,TRUE,TRUE,DATEADD('SECOND',X,CURRENT_TIMESTAMP) FROM SYSTEM_RANGE(1,2001)");
        jdbc.execute("INSERT INTO media_draft SELECT X,'FAILED' FROM SYSTEM_RANGE(1,2100)");
        sampler.sample();
        assertEquals(2000, pending("search"));
        assertEquals(1, registry.get(MetricNames.BUSINESS_PUBLICATION_CAPPED).gauge().value());
        assertTrue(Double.isNaN(registry.get(MetricNames.BUSINESS_PUBLICATION_OLDEST_SECONDS).tag("branch", "notice").gauge().value()));
        assertEquals(
                2001,
                registry.get(MetricNames.BUSINESS_DRAFTS_LOWERBOUND).tag("status", "FAILED").gauge().value());
        assertEquals(1, registry.get(MetricNames.BUSINESS_DRAFTS_CAPPED).tag("status", "FAILED").gauge().value());
    }

    @Test
    void scrapeReadsCachedValuesWithoutDatabaseQueries() {
        sampler.sample();
        jdbc.execute("DROP ALL OBJECTS");
        assertEquals(0, pending("search"));
        assertEquals(1, registry.get(MetricNames.PIPELINE_METRICS_SAMPLE_SUCCESS).gauge().value());
    }

    double pending(String branch) {
        return registry.get(MetricNames.BUSINESS_PUBLICATION_PENDING_LOWERBOUND).tag("branch", branch).gauge().value();
    }
}

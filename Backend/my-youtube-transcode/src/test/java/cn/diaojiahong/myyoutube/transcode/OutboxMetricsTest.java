package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.observability.OutboxMetrics;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxMetricsTest {
    OutboxDeliveryTest fixture;
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    OutboxMetrics sampler;
    @BeforeEach void setup() throws Exception {
        fixture = new OutboxDeliveryTest(); fixture.setup();
        jdbc = spy(fixture.jdbc);
        fixture.properties.getMonitoring().setEnabled(true);
        fixture.properties.getMonitoring().setPageLimit(2);
        sampler = new OutboxMetrics(jdbc, fixture.properties, fixture.clock, fixture.metrics);
    }
    double gauge(String name, String... tags) { return fixture.metrics.get(name).tags(tags).gauge().value(); }
    @Test void boundedStocksAgesAndScrapesDoNotQueryDatabase() {
        for (int i=0; i<5; i++) fixture.insert("pending-" + i, "{}");
        fixture.insert("sending", "{}"); fixture.jdbc.update("UPDATE pipeline_event_outbox SET status='SENDING' WHERE event_id='sending'");
        fixture.insert("quarantine", "{}"); fixture.jdbc.update("UPDATE pipeline_event_outbox SET status='SENT',quarantine_reason='EXPIRED_BUCKET' WHERE event_id='quarantine'");
        fixture.clock.advance(100); sampler.sample();
        assertEquals(2, gauge("pipeline.outbox.backlog", "status", "PENDING"));
        assertEquals(1, gauge("pipeline.outbox.backlog.capped", "status", "PENDING"));
        assertEquals(1, gauge("pipeline.outbox.backlog", "status", "QUARANTINED"));
        assertEquals(100, gauge("pipeline.outbox.oldest.seconds", "status", "SENDING"));
        clearInvocations(jdbc);
        fixture.metrics.getMeters().forEach(m -> m.measure().forEach(v -> v.getValue()));
        verifyNoInteractions(jdbc);
    }
    @Test void failureRetainsStockAndLastSuccessThenRecoveryUpdatesIt() throws Exception {
        fixture.insert("a", "{}"); sampler.sample(); fixture.clock.advance(60);
        fixture.jdbc.execute("DROP TABLE pipeline_event_outbox"); sampler.sample();
        assertEquals(1, gauge("pipeline.outbox.backlog", "status", "PENDING"));
        assertEquals(0, gauge("pipeline.metrics.sample.success", "component", "outbox"));
        assertEquals(60, gauge("pipeline.metrics.sample.age.seconds", "component", "outbox"));
        assertEquals(1, fixture.metrics.get("pipeline.metrics.sample.failures").counter().count());
        OutboxTestDatabase.reset(fixture.jdbc); sampler.sample();
        assertEquals(0, gauge("pipeline.outbox.backlog", "status", "PENDING"));
        assertEquals(1, gauge("pipeline.metrics.sample.success", "component", "outbox"));
    }
    @Test void restartRebuildsStockAndSamplingSwitchIsIndependent() {
        fixture.properties.getMonitoring().setEnabled(false); sampler.sample(); verifyNoInteractions(jdbc);
        assertTrue(Double.isNaN(gauge("pipeline.outbox.backlog", "status", "PENDING")));
        fixture.insert("a", "{}"); fixture.properties.getMonitoring().setEnabled(true);
        fixture.properties.getRecording().setEnabled(false); fixture.properties.getDelivery().setEnabled(false); fixture.properties.getCleanup().setEnabled(false);
        sampler.sample(); assertEquals(1, gauge("pipeline.outbox.backlog", "status", "PENDING"));
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var restarted = new OutboxMetrics(jdbc, fixture.properties, fixture.clock, registry); restarted.sample();
        assertEquals(1, registry.get("pipeline.outbox.backlog").tag("status", "PENDING").gauge().value());
        assertNull(registry.find("pipeline.outbox.sent").counter());
    }
}

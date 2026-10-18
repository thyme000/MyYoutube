package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.observability.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class OutboxDeliveryTest {
    static class MutableClock extends Clock {
        Instant instant = Instant.parse("2026-10-14T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
        void advance(long seconds) { instant = instant.plusSeconds(seconds); }
    }
    JdbcTemplate jdbc;
    OutboxStore store;
    PipelineObservationProperties properties;
    MutableClock clock;
    SimpleMeterRegistry metrics;
    @BeforeEach void setup() throws Exception {
        var ds = Boolean.getBoolean("content.integration") ? IsolatedDownloadSupport.database()
                : new DriverManagerDataSource("jdbc:h2:mem:outbox-delivery;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); OutboxTestDatabase.reset(jdbc);
        properties = new PipelineObservationProperties(); properties.getDelivery().setEnabled(true);
        clock = new MutableClock(); metrics = new SimpleMeterRegistry();
        store = new OutboxStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), properties, clock);
    }
    void insert(String id, String payload) {
        jdbc.update("INSERT INTO pipeline_event_outbox(event_id,aggregate_type,aggregate_id,event_sequence,occurred_at,payload,payload_digest,status,created_at) VALUES(?,'TASK',?,1,?,?,'digest','PENDING',?)",
                id, id, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), payload, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
    String status(String id) { return jdbc.queryForObject("SELECT status FROM pipeline_event_outbox WHERE event_id=?", String.class, id); }
    OutboxJobs jobs(OutboxPublisher publisher) { return new OutboxJobs(store, publisher, properties, metrics); }
    @Test void oneLowVolumeEventSendsInSameRoundOutsideTransactionAndDeliveryCanPause() {
        insert("a", "{\"event\":{\"id\":\"a\"}}");
        var received = new ArrayList<byte[]>();
        var jobs = jobs(body -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); received.add(body); });
        properties.getDelivery().setEnabled(false); jobs.deliver();
        assertEquals("PENDING", status("a")); assertTrue(received.isEmpty());
        properties.getDelivery().setEnabled(true); jobs.deliver();
        assertEquals(1, received.size()); assertEquals("SENT", status("a"));
    }
    @Test void uncertainSendReplaysIdenticalBodyOnlyAfterLeaseExpiry() {
        insert("a", "{\"event\":{\"id\":\"a\"}}");
        var received = new ArrayList<byte[]>();
        jobs(body -> { received.add(body); throw new IllegalStateException("ack lost"); }).deliver();
        assertEquals("SENDING", status("a"));
        jobs(received::add).deliver(); assertEquals(1, received.size());
        clock.advance(31); jobs(received::add).deliver();
        assertEquals(2, received.size()); assertArrayEquals(received.get(0), received.get(1));
        assertEquals("SENT", status("a"));
    }
    @Test void crashBeforeSendAllowsReclaimAndOldOwnerCannotRenewAckOrQuarantine() {
        insert("a", "{}"); var original = store.claim();
        assertTrue(store.claim().isEmpty());
        clock.advance(31); var replacement = store.claim();
        assertNotEquals(original.get(0).token(), replacement.get(0).token());
        assertFalse(store.renew(original)); assertEquals(0, store.sent(original));
        assertFalse(store.quarantine(original.get(0), "MESSAGE_TOO_LARGE"));
        assertTrue(store.renew(replacement)); assertEquals(1, store.sent(replacement));
    }
    @Test void concurrentClaimersNeverOwnSameRecord() {
        properties.getBatching().setClaimLimit(5);
        for (int i=0;i<10;i++) insert("event-" + i, "{}");
        var a = CompletableFuture.supplyAsync(store::claim); var b = CompletableFuture.supplyAsync(store::claim);
        var all = new ArrayList<>(a.join()); all.addAll(b.join());
        assertEquals(all.size(), all.stream().map(OutboxStore.Entry::id).distinct().count());
        all.addAll(store.claim());
        assertEquals(10, all.size()); assertEquals(10, all.stream().map(OutboxStore.Entry::id).distinct().count());
    }
    @Test void oversizedEventStaysIntactAndOtherEventsStillSend() {
        String large = "{\"text\":\"" + "中".repeat(100) + "\"}";
        insert("large", large); insert("small", "{}");
        properties.getBatching().setMaxBytes(64);
        var sent = new ArrayList<byte[]>(); jobs(sent::add).deliver();
        assertEquals(1, sent.size()); assertEquals("SENT", status("small")); assertEquals("PENDING", status("large"));
        assertEquals("MESSAGE_TOO_LARGE", jdbc.queryForObject("SELECT quarantine_reason FROM pipeline_event_outbox WHERE event_id='large'", String.class));
        assertEquals(large, jdbc.queryForObject("SELECT payload FROM pipeline_event_outbox WHERE event_id='large'", String.class));
        clock.advance(3600); assertTrue(store.claim().isEmpty());
    }
    @Test void oneFailedBatchDoesNotBlockOtherClaimedBatches() {
        insert("a", "{}"); insert("b", "{}"); properties.getBatching().setMaxEvents(1);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        jobs(body -> { if (count.incrementAndGet() == 1) throw new IllegalStateException("first failed"); }).deliver();
        assertEquals(2, count.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox WHERE status='SENT'", Integer.class));
    }
    @Test void cleanupIsIndependentBoundedAndUsesSentTimeWhileRetainingUnsentAndQuarantine() {
        for (String id : List.of("old", "old2", "recent", "pending", "sending", "quarantine")) insert(id, "{}");
        var old = LocalDateTime.ofInstant(clock.instant().minusSeconds(8 * 86400), ZoneOffset.UTC);
        jdbc.update("UPDATE pipeline_event_outbox SET created_at=?,sent_at=?,status='SENT'", old, old);
        jdbc.update("UPDATE pipeline_event_outbox SET sent_at=? WHERE event_id='recent'", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        jdbc.update("UPDATE pipeline_event_outbox SET status='PENDING' WHERE event_id='pending'");
        jdbc.update("UPDATE pipeline_event_outbox SET status='SENDING' WHERE event_id='sending'");
        jdbc.update("UPDATE pipeline_event_outbox SET quarantine_reason='manual' WHERE event_id='quarantine'");
        properties.getDelivery().setEnabled(false); var jobs = jobs(body -> fail("Delivery is disabled"));
        jobs.cleanup(); assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
        properties.getCleanup().setEnabled(true); properties.getBatching().setCleanupPageSize(1);
        jobs.cleanup(); assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
        jobs.cleanup(); assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
        jobs.cleanup(); assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
    }
}

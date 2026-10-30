package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.observability.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ObservationTransactionTest {
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    PipelineEventRecorder recorder;
    PipelineObservationProperties properties;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:observation;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        transaction = new TransactionTemplate(manager);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE media_task(id BIGINT PRIMARY KEY,status VARCHAR(32),event_sequence BIGINT DEFAULT 0)");
        jdbc.update("INSERT INTO media_task(id,status) VALUES(1,'READY')");
        OutboxTestDatabase.reset(jdbc);
        properties = new PipelineObservationProperties();
        properties.getRecording().setEnabled(true);
        recorder = new PipelineEventRecorder(jdbc, properties, manager);
    }

    PipelineEvent started(long sequence) {
        return new PipelineEvent(Instant.now(), new PipelineEvent.Service("transcode"),
                new PipelineEvent.Event("event-" + sequence, "ASR_STARTED", sequence),
                new PipelineEvent.Aggregate("TASK", "1"),
                null, "100", "10", "1", null, null, null, 1, null, null, null, null,
                new PipelineEvent.State("READY", "RUNNING"), null);
    }

    void change() {
        long current = jdbc.queryForObject("SELECT event_sequence FROM media_task WHERE id=1 FOR UPDATE", Long.class);
        long next = PipelineEvent.nextSequence(current);
        jdbc.update("UPDATE media_task SET status='RUNNING',event_sequence=? WHERE id=1", next);
        recorder.record(started(next));
    }

    @Test
    void stateSequenceAndEventCommitAndRollbackTogether() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
            change();
            throw new IllegalStateException("forced rollback");
        }));
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM media_task WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT event_sequence FROM media_task WHERE id=1", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
        transaction.executeWithoutResult(tx -> change());
        assertEquals(1, jdbc.queryForObject("SELECT event_sequence FROM media_task WHERE id=1", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT event_sequence FROM pipeline_event_outbox", Long.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM pipeline_event_outbox", String.class));
    }

    @Test
    void recordingOffKeepsExistingEventsAndRequiresNoTransaction() {
        assertThrows(IllegalStateException.class, () -> recorder.record(started(1)));
        transaction.executeWithoutResult(tx -> change());
        properties.getRecording().setEnabled(false);
        recorder.record(started(2));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
    }

    @Test
    void anotherDatasourceCannotJoinTheBusinessTransactionImplicitly() {
        var other = new DriverManagerDataSource("jdbc:h2:mem:observation-other;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PipelineEventRecorder(new JdbcTemplate(other), properties,
                new DataSourceTransactionManager(jdbc.getDataSource())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_event_outbox", Integer.class));
    }
}

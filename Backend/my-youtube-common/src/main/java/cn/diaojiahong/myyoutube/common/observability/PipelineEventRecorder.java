package cn.diaojiahong.myyoutube.common.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class PipelineEventRecorder {
    private final JdbcTemplate jdbc;
    private final PipelineObservationProperties properties;

    public PipelineEventRecorder(JdbcTemplate jdbc, PipelineObservationProperties properties,
                                 DataSourceTransactionManager transactionManager) {
        if (jdbc.getDataSource() == null || transactionManager.getDataSource() != jdbc.getDataSource())
            throw new IllegalArgumentException("Outbox must use the local business transaction manager datasource");
        this.jdbc = jdbc;
        this.properties = properties;
    }
    public void record(PipelineEvent event) {
        if (!properties.getRecording().isEnabled()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox recording requires the business transaction");
        var dataSource = java.util.Objects.requireNonNull(jdbc.getDataSource());
        if (!TransactionSynchronizationManager.hasResource(dataSource))
            throw new IllegalStateException("Outbox datasource is not bound to the business transaction");
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (!DataSourceUtils.isConnectionTransactional(connection, dataSource))
                throw new IllegalStateException("Outbox datasource must participate in the business transaction");
            jdbc.update("INSERT INTO pipeline_event_outbox (event_id,aggregate_type,aggregate_id,event_sequence,occurred_at,payload,payload_digest,status,created_at) VALUES (?,?,?,?,?,?,?,'PENDING',?)",
                    event.event().id(), event.aggregate().type(), event.aggregate().id(), event.event().sequence(),
                    java.time.LocalDateTime.ofInstant(event.occurredAt(), java.time.ZoneOffset.UTC),
                    PipelineEventJson.encode(event), PipelineEventJson.digest(event),
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        } finally { DataSourceUtils.releaseConnection(connection, dataSource); }
    }
}

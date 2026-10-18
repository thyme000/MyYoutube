package cn.diaojiahong.myyoutube.common.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

public final class OutboxStore {
    public record Entry(String id, String payload, String token) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PipelineObservationProperties properties;
    private final Clock clock;

    public OutboxStore(JdbcTemplate jdbc, TransactionTemplate businessTransaction, PipelineObservationProperties properties, Clock clock) {
        if (!(businessTransaction.getTransactionManager() instanceof org.springframework.jdbc.datasource.DataSourceTransactionManager manager)
                || jdbc.getDataSource() == null || manager.getDataSource() != jdbc.getDataSource())
            throw new IllegalArgumentException("Outbox store requires the local business datasource transaction manager");
        this.jdbc = jdbc; this.properties = properties; this.clock = clock;
        transaction = new TransactionTemplate(Objects.requireNonNull(businessTransaction.getTransactionManager()));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    public List<Entry> claim() {
        return transaction.execute(tx -> {
            var now = now();
            var token = UUID.randomUUID().toString();
            int limit = properties.getBatching().getClaimLimit();
            var rows = new ArrayList<>(select("status='SENDING' AND lease_until<=?", "lease_until,event_id", limit, token, now));
            if (rows.size() < limit) rows.addAll(select("status='PENDING'", "created_at,event_id", limit - rows.size(), token));
            for (var row : rows) jdbc.update("UPDATE pipeline_event_outbox SET status='SENDING',lease_token=?,lease_until=? WHERE event_id=?",
                    token, now.plusSeconds(properties.getBatching().getLeaseSeconds()), row.id());
            return List.copyOf(rows);
        });
    }
    private List<Entry> select(String predicate, String order, int limit, String token, Object... params) {
        var args = new ArrayList<>(Arrays.asList(params)); args.add(limit);
        return jdbc.query("SELECT event_id,payload FROM pipeline_event_outbox WHERE quarantine_reason IS NULL AND "
                + predicate + " ORDER BY " + order + " LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, index) -> new Entry(rs.getString(1), rs.getString(2), token), args.toArray());
    }
    public boolean renew(List<Entry> rows) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var now = now();
            for (var row : rows) if (jdbc.update("UPDATE pipeline_event_outbox SET lease_until=? WHERE event_id=? AND status='SENDING' AND lease_token=? AND lease_until>? AND quarantine_reason IS NULL",
                    now.plusSeconds(properties.getBatching().getLeaseSeconds()), row.id(), row.token(), now) != 1) {
                tx.setRollbackOnly(); return false;
            }
            return true;
        }));
    }
    public int sent(List<Entry> rows) {
        return Objects.requireNonNull(transaction.execute(tx -> {
            int total = 0;
            for (var row : rows) total += jdbc.update("UPDATE pipeline_event_outbox SET status='SENT',sent_at=?,lease_token=NULL,lease_until=NULL WHERE event_id=? AND status='SENDING' AND lease_token=? AND quarantine_reason IS NULL",
                    now(), row.id(), row.token());
            return total;
        }));
    }
    public boolean quarantine(Entry row, String reason) {
        return jdbc.update("UPDATE pipeline_event_outbox SET status='PENDING',quarantine_reason=?,lease_token=NULL,lease_until=NULL WHERE event_id=? AND status='SENDING' AND lease_token=?",
                reason, row.id(), row.token()) == 1;
    }
    public int cleanup() {
        return Objects.requireNonNull(transaction.execute(tx -> {
            var cutoff = now().minusDays(7);
            var ids = jdbc.queryForList("SELECT event_id FROM pipeline_event_outbox WHERE status='SENT' AND quarantine_reason IS NULL AND sent_at<? ORDER BY sent_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED",
                    String.class, cutoff, properties.getBatching().getCleanupPageSize());
            int count = 0;
            for (String id : ids) count += jdbc.update("DELETE FROM pipeline_event_outbox WHERE event_id=? AND status='SENT' AND quarantine_reason IS NULL AND sent_at<?", id, cutoff);
            return count;
        }));
    }
}

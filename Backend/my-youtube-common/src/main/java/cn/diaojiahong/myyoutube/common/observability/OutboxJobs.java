package cn.diaojiahong.myyoutube.common.observability;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
public final class OutboxJobs {
    private final OutboxStore store;
    private final OutboxPublisher publisher;
    private final PipelineObservationProperties properties;
    private final MeterRegistry metrics;

    public OutboxJobs(
            OutboxStore store,
            OutboxPublisher publisher,
            PipelineObservationProperties properties,
            MeterRegistry metrics) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${my-youtube.observability.scan-interval-ms:250}")
    public void deliver() {
        if (!properties.getDelivery().isEnabled()) {
            return;
        }

        try {
            PipelineObservationProperties.DeliverySettings settings = properties.getBatching();
            OutboxBatches.OutboxBatchResult split = OutboxBatches.split(
                    store.claim(),
                    settings.getMaxEvents(),
                    settings.getMaxBytes());

            for (OutboxStore.Entry row : split.oversized()) {
                if (store.quarantine(row, "MESSAGE_TOO_LARGE")) {
                    metrics.counter(MetricNames.PIPELINE_OUTBOX_QUARANTINED, "reason", "MESSAGE_TOO_LARGE").increment();
                    log.error("Outbox event quarantined eventId={} reason=MESSAGE_TOO_LARGE", row.id());
                }
            }

            for (OutboxBatches.Batch batch : split.batches()) {
                try {
                    if (!store.renew(batch.entries())) {
                        continue;
                    }
                    publisher.send(batch.body());
                    int sent = store.sent(batch.entries());
                    metrics.counter(MetricNames.PIPELINE_OUTBOX_SENT).increment(sent);
                } catch (Exception error) {
                    metrics.counter(MetricNames.PIPELINE_OUTBOX_DELIVERY_FAILURES).increment();
                    log.warn("Outbox batch deferred until lease expiry error={}", error.getClass().getSimpleName());
                }
            }
        } catch (Exception error) {
            metrics.counter(MetricNames.PIPELINE_OUTBOX_SCAN_FAILURES).increment();
            log.warn("Outbox scan failed error={}", error.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${my-youtube.observability.cleanup-interval-ms:60000}")
    public void cleanup() {
        if (!properties.getCleanup().isEnabled()) {
            return;
        }

        try {
            metrics.counter(MetricNames.PIPELINE_OUTBOX_CLEANED).increment(store.cleanup());
        } catch (Exception error) {
            metrics.counter(MetricNames.PIPELINE_OUTBOX_CLEANUP_FAILURES).increment();
            log.warn("Outbox cleanup failed error={}", error.getClass().getSimpleName());
        }
    }
}

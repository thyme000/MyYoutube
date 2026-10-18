package cn.diaojiahong.myyoutube.observability;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.client.consumer.listener.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;
import java.util.List;

final class BatchConsumer implements MessageListenerConcurrently {
    @FunctionalInterface interface Writer { void ingest(byte[] body); }
    @FunctionalInterface interface DeadLetters { void transfer(MessageExt message) throws Exception; }
    private static final Logger log = LoggerFactory.getLogger(BatchConsumer.class);
    private final Writer writer;
    private final DeadLetters dlq;
    private final PendingDlq pending;
    private final MeterRegistry metrics;
    BatchConsumer(Writer writer, DeadLetters dlq, PendingDlq pending, MeterRegistry metrics) {
        this.writer = writer; this.dlq = dlq; this.pending = pending; this.metrics = metrics;
    }
    @Override public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        // 一条 Broker 消息包含完整且不可变的事件批次。
        if (messages.size() != 1) return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        MessageExt message = messages.get(0);
        byte[] body = message.getBody();
        try {
            if (!pending.contains(body)) {
                try { writer.ingest(body); return ConsumeConcurrentlyStatus.CONSUME_SUCCESS; }
                catch (IngestFailure failure) {
                    if (!failure.permanent()) throw failure;
                    pending.save(body, failure.reason());
                    metrics.counter("pipeline.ingest.dlq.classified", "reason", failure.reason().name()).increment();
                    log.error("Pipeline batch requires DLQ transfer reason={}", failure.reason());
                }
            }
            dlq.transfer(message);
            pending.remove(body);
            metrics.counter("pipeline.ingest.dlq.transfers", "result", "confirmed").increment();
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            metrics.counter("pipeline.ingest.batches", "result", "retry").increment();
            log.warn("Pipeline batch unacknowledged errorType={}", failure.getClass().getSimpleName());
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }
}

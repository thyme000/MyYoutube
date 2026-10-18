package cn.diaojiahong.myyoutube.observability;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus.*;

class BatchConsumerTest {
    @TempDir Path directory;
    MessageExt message() { MessageExt m = new MessageExt(); m.setBody(new byte[]{1,2,3}); return m; }
    @Test void permanentFailureIsWrittenOnceEvenAfterRestartAndFailedDlqRpc() throws Exception {
        AtomicInteger writes = new AtomicInteger(); AtomicInteger transfers = new AtomicInteger();
        BatchConsumer.Writer writer = body -> { writes.incrementAndGet(); throw new IngestFailure(IngestFailure.Reason.MAPPING); };
        BatchConsumer.DeadLetters dlq = message -> { if (transfers.incrementAndGet() == 1) throw new java.io.IOException(); };
        var pending = new PendingDlq(directory, "test-group");
        var listener = new BatchConsumer(writer, dlq, pending, new SimpleMeterRegistry());
        assertEquals(RECONSUME_LATER, listener.consumeMessage(List.of(message()), null));
        assertTrue(pending.contains(message().getBody()));
        listener = new BatchConsumer(writer, dlq, new PendingDlq(directory, "test-group"), new SimpleMeterRegistry());
        assertEquals(CONSUME_SUCCESS, listener.consumeMessage(List.of(message()), null));
        assertEquals(1, writes.get()); assertEquals(2, transfers.get());
        assertFalse(pending.contains(message().getBody()));
    }
    @Test void temporaryFailureDoesNotTransferOrMarkAndRetriesOriginalBytes() throws Exception {
        AtomicInteger writes = new AtomicInteger(); var pending = new PendingDlq(directory, "test-group");
        var listener = new BatchConsumer(body -> {
            assertArrayEquals(message().getBody(), body);
            if (writes.incrementAndGet() == 1) throw new IngestFailure(IngestFailure.Reason.UNAVAILABLE);
        }, message -> fail("Temporary error cannot directly transfer"), pending, new SimpleMeterRegistry());
        assertEquals(RECONSUME_LATER, listener.consumeMessage(List.of(message()), null));
        assertFalse(pending.contains(message().getBody()));
        assertEquals(CONSUME_SUCCESS, listener.consumeMessage(List.of(message()), null));
    }
    @Test void markerWriteFailurePreventsAckAndTransfer() throws Exception {
        var pending = new PendingDlq(directory.resolve("pending"), "test-group");
        Files.delete(directory.resolve("pending")); Files.writeString(directory.resolve("pending"), "unwritable-directory");
        var listener = new BatchConsumer(body -> { throw new IngestFailure(IngestFailure.Reason.INVALID_BATCH); },
                message -> fail("Cannot transfer before durable classification"), pending, new SimpleMeterRegistry());
        assertEquals(RECONSUME_LATER, listener.consumeMessage(List.of(message()), null));
    }
}

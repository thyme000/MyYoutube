package cn.diaojiahong.myyoutube.common.observability;

import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.common.message.Message;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxBatchesTest {
    OutboxStore.Entry row(String id, String json) { return new OutboxStore.Entry(id, json, "lease"); }
    @Test void actualEnvelopeUtf8BoundaryIncludesCommasAndMultibyteText() {
        var rows = List.of(row("1", "{\"text\":\"中文😀\"}"), row("2", "{\"text\":\"中文😀\"}"));
        byte[] both = OutboxBatches.split(rows, 100, 262144).batches().get(0).body();
        assertEquals(1, OutboxBatches.split(rows, 100, both.length).batches().size());
        var split = OutboxBatches.split(rows, 100, both.length - 1);
        assertEquals(2, split.batches().size());
        assertTrue(split.batches().stream().allMatch(b -> b.body().length <= both.length - 1));
        int single = split.batches().get(0).body().length;
        assertEquals(2, OutboxBatches.split(rows, 100, single - 1).oversized().size());
        assertEquals(2, OutboxBatches.split(rows, 1, both.length).batches().size());
        assertTrue(OutboxBatches.split(List.of(), 100, 262144).batches().isEmpty());
    }
    @Test void oversizedItemDoesNotBlockNormalRecordsAndBodyIsOneBatchMessage() {
        var result = OutboxBatches.split(List.of(row("large", "{\"text\":\"" + "中".repeat(100) + "\"}"), row("small", "{}")), 100, 64);
        assertEquals(List.of("large"), result.oversized().stream().map(OutboxStore.Entry::id).toList());
        assertEquals("{\"schemaVersion\":1,\"events\":[{}]}", new String(result.batches().get(0).body(), StandardCharsets.UTF_8));
    }
    @Test void adapterUsesExactBytesAndRequiresUnambiguousBrokerAcknowledgement() throws Exception {
        var mq = mock(RocketMQTemplate.class); var producer = mock(DefaultMQProducer.class);
        when(mq.getProducer()).thenReturn(producer);
        var result = new SendResult(); result.setSendStatus(SendStatus.SEND_OK);
        when(producer.send(any(Message.class))).thenReturn(result);
        var publisher = new RocketMqOutboxPublisher(mq); byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        publisher.send(body);
        var capture = org.mockito.ArgumentCaptor.forClass(Message.class); verify(producer).send(capture.capture());
        assertArrayEquals(body, capture.getValue().getBody());
        result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        assertThrows(IllegalStateException.class, () -> publisher.send(body));
    }
}

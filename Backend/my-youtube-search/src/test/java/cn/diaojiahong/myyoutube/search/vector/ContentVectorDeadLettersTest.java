package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort.Reference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentVectorDeadLettersTest {
    private final DefaultMQPullConsumer consumer = mock(DefaultMQPullConsumer.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContentVectorDeadLetters reader = new ContentVectorDeadLetters(consumer, mapper);
    private final MessageQueue first = new MessageQueue("%DLQ%" + MqConstants.GROUP_CONTENT_VECTOR, "a", 0);
    private final MessageQueue second = new MessageQueue(first.getTopic(), "b", 0);

    @Test void pagesAcrossBrokersCanBeReadAgainWithoutAdvancingConsumption() throws Exception {
        when(consumer.fetchSubscribeMessageQueues(first.getTopic())).thenReturn(Set.of(second, first));
        when(consumer.minOffset(first)).thenReturn(2L); when(consumer.maxOffset(first)).thenReturn(4L);
        when(consumer.minOffset(second)).thenReturn(5L); when(consumer.maxOffset(second)).thenReturn(6L);
        when(consumer.pull(first, "*", 2, 1, 3000)).thenReturn(found(3, message(2, "{\"contentId\":10}")));
        when(consumer.pull(first, "*", 3, 1, 3000)).thenReturn(found(4, message(3, "{\"contentId\":11}")));
        when(consumer.pull(second, "*", 5, 1, 3000)).thenReturn(found(6, message(5, "{\"contentId\":12}")));

        ContentVectorDeadLetters.Page page1 = reader.query(null, 1);
        ContentVectorDeadLetters.Page page2 = reader.query(page1.nextCursor(), 1);
        ContentVectorDeadLetters.Page page3 = reader.query(page2.nextCursor(), 1);
        assertEquals(10L, page1.entries().get(0).contentId());
        assertEquals(11L, page2.entries().get(0).contentId());
        assertEquals(12L, page3.entries().get(0).contentId());
        assertNull(page3.nextCursor());
        assertEquals(page1, reader.query(null, 1));
        verify(consumer, times(1)).start();
        verify(consumer, never()).updateConsumeOffset(any(), anyLong());
        reader.close(); verify(consumer).shutdown();
    }

    @Test void absentTopicIsEmptyButBrokerErrorsAreNotHidden() throws Exception {
        when(consumer.fetchSubscribeMessageQueues(first.getTopic()))
                .thenThrow(new MQClientException(ResponseCode.TOPIC_NOT_EXIST, "absent"));
        assertTrue(reader.query(null, 20).entries().isEmpty());
        doThrow(new MQClientException("unavailable", null)).when(consumer).fetchSubscribeMessageQueues(first.getTopic());
        assertThrows(IllegalStateException.class, () -> reader.query(null, 20));
    }

    @Test void malformedAndExpiredCursorsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> reader.query("not-json", 20));
        assertThrows(IllegalArgumentException.class, () -> reader.query(null, 51));
        assertThrows(IllegalArgumentException.class, () -> reader.query(null, 0));
        when(consumer.fetchSubscribeMessageQueues(first.getTopic())).thenReturn(Set.of(first));
        when(consumer.minOffset(first)).thenReturn(5L); when(consumer.maxOffset(first)).thenReturn(10L);
        String cursor = Base64.getUrlEncoder().encodeToString(mapper.writeValueAsBytes(new Reference("a", 0, 4L)));
        assertThrows(IllegalArgumentException.class, () -> reader.query(cursor, 20));
    }

    @Test void replayReadsOnlyTheRequestedOffsetInTheFixedDeadLetterTopic() throws Exception {
        when(consumer.fetchSubscribeMessageQueues(first.getTopic())).thenReturn(Set.of(first));
        when(consumer.minOffset(first)).thenReturn(2L); when(consumer.maxOffset(first)).thenReturn(5L);
        Reference reference = new Reference("a", 0, 2L);
        when(consumer.pull(first, "*", 2, 1, 3000)).thenReturn(found(4, message(3, "{\"contentId\":20}")));
        assertNull(reader.find(reference));
        when(consumer.pull(first, "*", 2, 1, 3000)).thenReturn(found(3, message(2, "{\"contentId\":10}")));
        assertEquals(10L, reader.find(reference).contentId());
        assertNull(reader.find(new Reference("unknown-broker", 0, 2L)));
        assertNull(reader.find(new Reference("a", 0, 1L)));
        assertThrows(IllegalArgumentException.class, () -> reader.find(new Reference("a", 0, null)));
        verify(consumer, never()).pull(argThat(queue -> !first.getTopic().equals(queue.getTopic())), anyString(), anyLong(), anyInt(), anyLong());
        verify(consumer, never()).updateConsumeOffset(any(), anyLong());
    }

    @Test void invalidPayloadStaysVisibleAndCannotSupplyAContentId() throws Exception {
        when(consumer.fetchSubscribeMessageQueues(first.getTopic())).thenReturn(Set.of(first));
        when(consumer.maxOffset(first)).thenReturn(3L);
        when(consumer.pull(first, "*", 0, 20, 3000)).thenReturn(new PullResult(PullStatus.FOUND, 3, 0, 3,
                List.of(message(0, "invalid"), message(1, "{\"contentId\":0}"), message(2, "null"))));
        ContentVectorDeadLetters.Page page = reader.query(null, 20);
        assertEquals(3, page.entries().size());
        assertTrue(page.entries().stream().allMatch(entry -> entry.contentId() == null));
    }

    private PullResult found(long next, MessageExt message) {
        return new PullResult(PullStatus.FOUND, next, 0, next, List.of(message));
    }

    private MessageExt message(long offset, String body) {
        var message = new MessageExt();
        message.setBody(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        message.setQueueOffset(offset); message.setMsgId("message-" + offset); message.setStoreTimestamp(100);
        return message;
    }
}

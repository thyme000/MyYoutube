package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ContentVectorMessage;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentVectorPublisherTest {
    final RocketMQTemplate mq = mock(RocketMQTemplate.class);
    final VectorProperties properties = new VectorProperties();
    final ContentVectorPublisher publisher = new ContentVectorPublisher(mq, properties);

    @ParameterizedTest
    @EnumSource(SendStatus.class)
    void acceptsOnlySuccessfulBrokerAcknowledgement(SendStatus status) {
        var result = new SendResult(); result.setSendStatus(status);
        when(mq.syncSend(eq(MqConstants.TOPIC_CONTENT_VECTOR), any(Message.class))).thenReturn(result);
        if (status == SendStatus.SEND_OK) assertDoesNotThrow(() -> publisher.publish(1L));
        else assertThrows(IllegalStateException.class, () -> publisher.publish(1L));

        var message = ArgumentCaptor.forClass(Message.class);
        verify(mq).syncSend(eq(MqConstants.TOPIC_CONTENT_VECTOR), message.capture());
        assertEquals(new ContentVectorMessage(1L), message.getValue().getPayload());
        assertEquals("1", message.getValue().getHeaders().get(RocketMQHeaders.KEYS));
    }

    @Test void missingAcknowledgementAndSendExceptionRemainFailures() {
        assertThrows(IllegalStateException.class, () -> publisher.publish(1L));
        when(mq.syncSend(eq(MqConstants.TOPIC_CONTENT_VECTOR), any(Message.class)))
                .thenThrow(new IllegalStateException("Broker unavailable"));
        assertThrows(IllegalStateException.class, () -> publisher.publish(1L));
    }

    @Test void disabledVectorSearchDoesNotPublishTasks() {
        properties.setEnabled(false);
        publisher.publish(1L);
        verifyNoInteractions(mq);
    }

    @Test void recoveryCarriesVersionAndFailureAndRequiresDeliveryConfirmation() {
        var result = new SendResult(); result.setSendStatus(SendStatus.SEND_OK);
        when(mq.syncSend(eq(MqConstants.TOPIC_CONTENT_VECTOR), any(Message.class))).thenReturn(result);
        publisher.publishRecovery(1L, "model:2", "write failed");
        var message = ArgumentCaptor.forClass(Message.class);
        verify(mq).syncSend(eq(MqConstants.TOPIC_CONTENT_VECTOR), message.capture());
        assertEquals(new ContentVectorMessage(1L, "model:2", "write failed"), message.getValue().getPayload());
        properties.setEnabled(false);
        assertThrows(IllegalStateException.class, () -> publisher.publishRecovery(1L, "model:2", "write failed"));
    }
}

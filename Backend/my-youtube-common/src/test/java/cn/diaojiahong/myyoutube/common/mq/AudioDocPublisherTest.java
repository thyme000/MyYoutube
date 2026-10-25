package cn.diaojiahong.myyoutube.common.mq;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioDocPublisherTest {
    private final RocketMQTemplate mq = mock(RocketMQTemplate.class);
    private final EsSyncPublisher publisher = new EsSyncPublisher(mq);

    @ParameterizedTest @EnumSource(SendStatus.class)
    void requiresConfirmedSend(SendStatus status) {
        var result = new SendResult(); result.setSendStatus(status);
        when(mq.syncSendDelayTimeSeconds(anyString(), any(Object.class), anyLong())).thenReturn(result);
        if (status == SendStatus.SEND_OK) assertDoesNotThrow(() -> publisher.syncAudioDoc(1));
        else assertThrows(MqSendUnconfirmedException.class, () -> publisher.syncAudioDoc(1));
    }

    @Test void missingAcknowledgementIsNotSuccess() {
        assertThrows(MqSendUnconfirmedException.class, () -> publisher.syncAudioDoc(1));
    }
}

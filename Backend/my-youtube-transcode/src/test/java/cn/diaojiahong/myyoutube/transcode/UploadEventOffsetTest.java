package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.transcode.mq.listener.MediaUploadEventListener;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.store.OffsetStore;
import org.apache.rocketmq.client.consumer.store.ReadOffsetType;
import org.apache.rocketmq.client.impl.MQAdminImpl;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;
import org.apache.rocketmq.client.impl.consumer.RebalancePushImpl;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class UploadEventOffsetTest {
    @Test void firstUploadBeforeQueueAssignmentIsNotSkipped() throws Exception {
        assertEquals(0, initialOffset(-1, 1));
    }

    @Test void establishedGroupResumesFromItsStoredOffset() throws Exception {
        assertEquals(7, initialOffset(7, 10));
    }

    private long initialOffset(long stored, long brokerHead) throws Exception {
        var policy = MediaUploadEventListener.class.getAnnotation(RocketMQMessageListener.class);
        var consumer = new DefaultMQPushConsumer(policy.consumerGroup());
        consumer.setConsumeFromWhere(policy.consumeFromWhere());
        var queue = new MessageQueue(MqConstants.TOPIC_MEDIA_UPLOAD_EVENT, "broker-a", 0);
        var offsets = mock(OffsetStore.class);
        when(offsets.readOffset(queue, ReadOffsetType.READ_FROM_STORE)).thenReturn(stored);
        var admin = mock(MQAdminImpl.class);
        when(admin.maxOffset(queue)).thenReturn(brokerHead);
        var client = mock(MQClientInstance.class);
        when(client.getMQAdminImpl()).thenReturn(admin);
        var implementation = mock(DefaultMQPushConsumerImpl.class);
        when(implementation.getDefaultMQPushConsumer()).thenReturn(consumer);
        when(implementation.getOffsetStore()).thenReturn(offsets);
        var rebalance = new RebalancePushImpl(implementation);
        rebalance.setmQClientFactory(client);
        return rebalance.computePullFromWhereWithException(queue);
    }
}

package cn.diaojiahong.myyoutube.common.observability;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class RocketMqOutboxPublisher implements OutboxPublisher {
    private final RocketMQTemplate mq;

    public RocketMqOutboxPublisher(RocketMQTemplate mq) {
        this.mq = mq;
    }

    @Override public void send(byte[] body) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox MQ delivery cannot run inside a database transaction");
        SendResult result = mq.getProducer().send(new Message(MqConstants.TOPIC_PIPELINE_EVENTS, body));
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK)
            throw new IllegalStateException("Broker acknowledgement is uncertain");
    }
}

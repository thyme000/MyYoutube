package cn.diaojiahong.myyoutube.common.observability;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class RocketMqOutboxPublisher implements OutboxPublisher {
    public static final String TOPIC = "pipeline_event_topic";

    private final RocketMQTemplate mq;

    public RocketMqOutboxPublisher(RocketMQTemplate mq) {
        this.mq = mq;
    }

    @Override public void send(byte[] body) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox MQ delivery cannot run inside a database transaction");
        SendResult result = mq.getProducer().send(new Message(TOPIC, body));
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK)
            throw new IllegalStateException("Broker acknowledgement is uncertain");
    }
}

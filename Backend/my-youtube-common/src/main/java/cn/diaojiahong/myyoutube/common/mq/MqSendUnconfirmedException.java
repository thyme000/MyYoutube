package cn.diaojiahong.myyoutube.common.mq;

import org.apache.rocketmq.client.producer.SendStatus;

public final class MqSendUnconfirmedException extends IllegalStateException {
    public MqSendUnconfirmedException(SendStatus status) { super("MQ send not confirmed: " + status); }
}

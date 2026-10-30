package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ContentVectorMessage;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentVectorPublisher {
    private final RocketMQTemplate mq;
    private final VectorProperties properties;

    public void publish(Long contentId) {
        if (!properties.isEnabled()) return;
        send(new ContentVectorMessage(contentId));
    }

    public void publishRecovery(Long contentId, String targetVersion, String failureReason) {
        if (!properties.isEnabled()) throw new IllegalStateException("向量功能已关闭，恢复任务未登记");
        if (targetVersion == null || targetVersion.isBlank()) throw new IllegalArgumentException("恢复任务缺少目标版本");
        send(new ContentVectorMessage(contentId, targetVersion, failureReason));
    }

    private void send(ContentVectorMessage payload) {
        Message<ContentVectorMessage> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, payload.contentId().toString()).build();
        SendResult result = mq.syncSend(MqConstants.TOPIC_CONTENT_VECTOR, message);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("向量任务未获 Broker 成功确认, contentId=" + payload.contentId());
        }
    }
}

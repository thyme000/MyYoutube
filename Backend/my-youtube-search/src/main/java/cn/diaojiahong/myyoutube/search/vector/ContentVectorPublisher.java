package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ContentVectorMessage;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentVectorPublisher {
    private final RocketMQTemplate mq;
    private final VectorProperties properties;

    public void publish(Long contentId) {
        if (!properties.isEnabled()) return;
        var message = MessageBuilder.withPayload(new ContentVectorMessage(contentId))
                .setHeader(RocketMQHeaders.KEYS, contentId.toString()).build();
        var result = mq.syncSend(MqConstants.TOPIC_CONTENT_VECTOR, message);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("向量任务未获 Broker 成功确认, contentId=" + contentId);
        }
    }
}

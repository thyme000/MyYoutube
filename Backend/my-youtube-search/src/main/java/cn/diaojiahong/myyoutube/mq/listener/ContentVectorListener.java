package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.mq.message.ContentVectorMessage;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "my-youtube.search.vector",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@RocketMQMessageListener(topic = ContentVectorMessage.TOPIC, consumerGroup = ContentVectorMessage.GROUP,
        consumeThreadNumber = 1, consumeThreadMax = 1, consumeTimeout = 5, maxReconsumeTimes = 3,
        consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET)
public class ContentVectorListener implements RocketMQListener<ContentVectorMessage> {
    private final ContentVectorService vectors;

    @Override
    public void onMessage(ContentVectorMessage message) {
        if (message == null || message.contentId() == null || message.contentId() <= 0) {
            throw new IllegalArgumentException("Invalid content vector message");
        }

        if (message.targetVersion() != null && message.targetVersion().isBlank()) {
            throw new IllegalArgumentException("Invalid content vector target version");
        }

        vectors.embedContent(message.contentId(), message.targetVersion());
    }
}

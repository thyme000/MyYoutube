package cn.diaojiahong.myyoutube.mq.listener;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationBranch;
import cn.diaojiahong.myyoutube.content.service.PublicationEffectsService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = PublicationEffectMessage.TOPIC,
        selectorExpression = "SEARCH",
        consumerGroup = "content_publication_search",
        maxReconsumeTimes = 3)
public class SearchPublicationListener implements RocketMQListener<PublicationEffectMessage> {
    private final PublicationEffectsService effects;

    @Override
    public void onMessage(PublicationEffectMessage message) {
        if (message.branch() != PublicationBranch.SEARCH) {
            throw new IllegalArgumentException("Publication branch mismatch");
        }
        effects.apply(message);
    }
}

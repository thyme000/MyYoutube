package cn.diaojiahong.myyoutube.mq.listener;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage;
import cn.diaojiahong.myyoutube.video.service.PublicationEffectsService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstants.TOPIC_PUBLICATION_EFFECT, selectorExpression = "COUNT", consumerGroup = "video_publication_count", maxReconsumeTimes = 3)
public class CountPublicationListener implements RocketMQListener<PublicationEffectMessage> {
    private final PublicationEffectsService effects;
    @Override public void onMessage(PublicationEffectMessage message) {
        if (!"COUNT".equals(message.branch())) throw new IllegalArgumentException("Publication branch mismatch");
        effects.apply(message);
    }
}

package cn.diaojiahong.myyoutube.mq.listener;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationEffectMessage;
import cn.diaojiahong.myyoutube.common.mq.message.PublicationBranch;
import cn.diaojiahong.myyoutube.video.service.PublicationEffectsService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_PUBLICATION_EFFECT,
        selectorExpression = "NOTICE",
        consumerGroup = "video_publication_notice",
        maxReconsumeTimes = 3)
public class NoticePublicationListener implements RocketMQListener<PublicationEffectMessage> {
    private final PublicationEffectsService effects;

    @Override
    public void onMessage(PublicationEffectMessage message) {
        if (message.branch() != PublicationBranch.NOTICE) {
            throw new IllegalArgumentException("Publication branch mismatch");
        }
        effects.apply(message);
    }
}

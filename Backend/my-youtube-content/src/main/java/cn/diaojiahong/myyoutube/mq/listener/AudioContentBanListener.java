package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import cn.diaojiahong.myyoutube.content.service.ReviewDecisionService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = AudioContentBanMessage.TOPIC, consumerGroup = "content_audio_content_ban",
        consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET, maxReconsumeTimes = 3)
public class AudioContentBanListener implements RocketMQListener<AudioContentBanMessage> {
    private final ReviewDecisionService decisions;

    @Override
    public void onMessage(AudioContentBanMessage message) {
        decisions.banByAudio(message);
    }
}

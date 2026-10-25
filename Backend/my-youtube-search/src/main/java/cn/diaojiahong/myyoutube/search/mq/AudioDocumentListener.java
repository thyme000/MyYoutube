package cn.diaojiahong.myyoutube.search.mq;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.Sync;
import cn.diaojiahong.myyoutube.search.service.AudioSearchService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstants.TOPIC_ES_DOCUMENT, selectorExpression = MqConstants.TAG_ES_AUDIO,
        consumerGroup = "search_audio_document", maxReconsumeTimes = 3)
public class AudioDocumentListener implements RocketMQListener<Sync> {
    private final AudioSearchService service;
    @Override public void onMessage(Sync message) { service.rebuild(message.audioId()); }
}

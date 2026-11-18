package cn.diaojiahong.myyoutube.search.mq;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.Sync;
import cn.diaojiahong.myyoutube.search.service.AudioSearchService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = ContentDocMessage.TOPIC, selectorExpression = AudioDiscoveryContracts.Sync.TAG,
        consumerGroup = "search_audio_document", maxReconsumeTimes = 3)
public class AudioDocumentListener implements RocketMQListener<Sync> {
    private final AudioSearchService service;

    @Override
    public void onMessage(Sync message) {
        service.rebuild(message.audioId());
    }
}

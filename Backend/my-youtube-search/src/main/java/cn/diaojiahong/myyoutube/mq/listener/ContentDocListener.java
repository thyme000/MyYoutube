package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_ES_DOCUMENT,
        consumerGroup = "es_document_content_group",
        selectorExpression = MqConstants.TAG_ES_CONTENT,
        maxReconsumeTimes = 3)
public class ContentDocListener implements RocketMQListener<ContentDocMessage> {

    private final cn.diaojiahong.myyoutube.search.service.ContentDocumentWriter writer;
    private final cn.diaojiahong.myyoutube.search.port.ContentDocPortImpl search;

    @Override
    public void onMessage(ContentDocMessage message) {
        search.rebuildContent(message.getContentId());
    }
}

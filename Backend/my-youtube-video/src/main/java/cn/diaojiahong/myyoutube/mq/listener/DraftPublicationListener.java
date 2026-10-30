package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.DraftPublicationMessage;
import cn.diaojiahong.myyoutube.content.service.DraftPublicationService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_DRAFT_PUBLICATION,
        consumerGroup = "video_draft_publication",
        maxReconsumeTimes = 3)
public class DraftPublicationListener implements RocketMQListener<DraftPublicationMessage> {
    private final DraftPublicationService publications;

    @Override
    public void onMessage(DraftPublicationMessage message) {
        publications.consume(message);
    }
}

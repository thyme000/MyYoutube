package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ContentUnderstoodMessage;
import cn.diaojiahong.myyoutube.video.service.ContentUnderstandingService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstants.TOPIC_CONTENT_UNDERSTOOD, consumerGroup = "video_content_understood", maxReconsumeTimes = 3)
public class ContentUnderstoodListener implements RocketMQListener<ContentUnderstoodMessage> {
    private final ContentUnderstandingService understanding;
    @Override public void onMessage(ContentUnderstoodMessage message) {
        if (message != null && message.manifest() != null) understanding.accept(message);
    }
}

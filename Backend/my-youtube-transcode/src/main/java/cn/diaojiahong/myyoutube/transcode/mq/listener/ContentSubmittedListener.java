package cn.diaojiahong.myyoutube.transcode.mq.listener;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ContentSubmittedMessage;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstants.TOPIC_CONTENT_SUBMITTED, consumerGroup = "transcode_content_submitted", maxReconsumeTimes = 3)
public class ContentSubmittedListener implements RocketMQListener<ContentSubmittedMessage> {
    private final MediaTaskService tasks;
    @Override public void onMessage(ContentSubmittedMessage message) { tasks.start(message.input()); }
}

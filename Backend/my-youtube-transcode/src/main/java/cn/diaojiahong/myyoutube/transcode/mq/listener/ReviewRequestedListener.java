package cn.diaojiahong.myyoutube.transcode.mq.listener;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.ReviewRequestedMessage;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_REVIEW_REQUESTED,
        consumerGroup = "transcode_review_requested",
        maxReconsumeTimes = 3)
public class ReviewRequestedListener implements RocketMQListener<ReviewRequestedMessage> {
    private final MediaTaskService tasks;

    @Override
    public void onMessage(ReviewRequestedMessage message) {
        tasks.startReview(message.pipelineId());
    }
}

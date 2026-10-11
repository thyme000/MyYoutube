package cn.diaojiahong.myyoutube.mq.listener;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.ReviewResult;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.video.service.ReviewDecisionService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstants.TOPIC_REVIEW_RESULT, consumerGroup = "video_review_result", maxReconsumeTimes = 3)
public class ReviewResultListener implements RocketMQListener<ReviewResult> {
    private final ReviewDecisionService decisions;
    private final MediaTaskPort tasks;
    @Override public void onMessage(ReviewResult result) {
        decisions.acceptResult(result);
        tasks.resultReceived(result.taskId(), result.attempt());
    }
}

package cn.diaojiahong.myyoutube.mq.listener;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.ReviewResult;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.content.service.ReviewDecisionService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = PipelineContracts.ReviewResult.TOPIC,
        consumerGroup = "content_review_result",
        maxReconsumeTimes = 3)
public class ReviewResultListener implements RocketMQListener<ReviewResult> {
    private final ReviewDecisionService decisions;
    private final MediaTaskPort tasks;

    @Override
    public void onMessage(ReviewResult result) {
        decisions.acceptResult(result);
        tasks.resultReceived(result.taskId(), result.attempt());
    }
}

package cn.diaojiahong.myyoutube.transcode.mq.listener;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskWorker;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstants.TOPIC_MEDIA_TASK, consumerGroup = "transcode_draft_worker",
        selectorExpression = "DRAFT", consumeThreadNumber = 2, consumeThreadMax = 2, maxReconsumeTimes = 3)
public class DraftWorkerListener implements RocketMQListener<MediaTaskMessage>, org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener {
    private final MediaTaskWorker worker;
    @org.springframework.beans.factory.annotation.Value("${my-youtube.media-pipeline.draft-concurrency:2}")
    private int concurrency;
    @Override public void prepareStart(org.apache.rocketmq.client.consumer.DefaultMQPushConsumer consumer) {
        if (concurrency < 1) throw new IllegalStateException("Worker concurrency must be positive");
        consumer.setConsumeThreadMin(concurrency); consumer.setConsumeThreadMax(concurrency);
    }
    @Override public void onMessage(MediaTaskMessage message) {
        if (!"DRAFT".equals(message.getPhase())) throw new IllegalArgumentException("Worker phase mismatch");
        worker.execute(message);
    }
}

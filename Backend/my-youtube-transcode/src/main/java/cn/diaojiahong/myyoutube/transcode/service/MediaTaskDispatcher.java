package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.*;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts;

@Service
@RequiredArgsConstructor
public class MediaTaskDispatcher {
    private final RocketMQTemplate mq;

    public void dispatch(MediaTaskMessage message) {
        mq.syncSend(MediaTaskMessage.TOPIC + ":" + message.getPhase(), message);
    }

    public void completed(Manifest manifest) {
        mq.syncSend(ContentUnderstoodMessage.TOPIC, new ContentUnderstoodMessage(manifest));
    }

    public void reviewed(ReviewResult result) {
        mq.syncSend(PipelineContracts.ReviewResult.TOPIC, result);
    }
}

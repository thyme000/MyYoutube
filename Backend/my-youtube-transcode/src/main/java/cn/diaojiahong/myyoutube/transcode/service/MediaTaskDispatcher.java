package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.*;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MediaTaskDispatcher {
    private final RocketMQTemplate mq;

    public void dispatch(MediaTaskMessage message) {
        mq.syncSend(MqConstants.TOPIC_MEDIA_TASK + ":" + message.getPhase(), message);
    }

    public void completed(Manifest manifest) {
        mq.syncSend(MqConstants.TOPIC_CONTENT_UNDERSTOOD, new ContentUnderstoodMessage(manifest));
    }

    public void reviewed(ReviewResult result) {
        mq.syncSend(MqConstants.TOPIC_REVIEW_RESULT, result);
    }
}

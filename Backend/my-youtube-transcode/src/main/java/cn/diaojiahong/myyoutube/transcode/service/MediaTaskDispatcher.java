package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.*;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MediaTaskDispatcher {
    private final RocketMQTemplate mq;
    private final MediaDraftPort drafts;
    public void dispatch(MediaTaskMessage message) {
        mq.syncSend(MqConstants.TOPIC_MEDIA_TASK + ":" + message.getPhase(), message);
    }
    public void completed(Manifest manifest) {
        mq.syncSend(MqConstants.TOPIC_CONTENT_UNDERSTOOD, new ContentUnderstoodMessage(manifest));
    }
    public void reviewed(ReviewResult result) { mq.syncSend(MqConstants.TOPIC_REVIEW_RESULT, result); }
    public void draftFinished(Long id, boolean success, Long duration, String error) {
        drafts.updateStatus(id, success ? "ready" : "failed", new MediaDraftPort.StatusUpdate(duration, error));
    }
}

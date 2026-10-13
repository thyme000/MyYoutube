package cn.diaojiahong.myyoutube.transcode.mq.listener;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaUploadEventMessage;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = MqConstants.TOPIC_MEDIA_UPLOAD_EVENT, consumerGroup = "transcode_media_upload_event_group", maxReconsumeTimes = 3)
public class MediaUploadEventListener implements RocketMQListener<MediaUploadEventMessage> {
    private final MediaDraftPort drafts;
    @Override public void onMessage(MediaUploadEventMessage message) {
        var draft = drafts.getByObjectKey(message.getObjectKey());
        if (draft == null || !java.util.Set.of("uploading", "processing").contains(draft.getStatus())) return;
        drafts.uploaded(draft.getId());
    }
}

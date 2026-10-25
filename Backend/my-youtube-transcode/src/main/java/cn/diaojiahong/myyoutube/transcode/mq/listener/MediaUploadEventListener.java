package cn.diaojiahong.myyoutube.transcode.mq.listener;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaUploadEventMessage;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import cn.diaojiahong.myyoutube.video.api.AudioUploadPort;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
// 首条上传事件可能先于新消费组的队列分配到达；已有消费进度仍优先续读。
@RocketMQMessageListener(topic = MqConstants.TOPIC_MEDIA_UPLOAD_EVENT, consumerGroup = "transcode_media_upload_event_group",
        consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET, maxReconsumeTimes = 3)
public class MediaUploadEventListener implements RocketMQListener<MediaUploadEventMessage> {
    private final MediaDraftPort drafts;
    private final AudioUploadPort audios;
    @Override public void onMessage(MediaUploadEventMessage message) {
        String key = message.getObjectKey();
        if (key != null && (key.startsWith("uploads/audio/") || key.startsWith("uploads/audio-cover/"))) {
            audios.uploaded(key);
            return;
        }
        var draft = drafts.getByObjectKey(message.getObjectKey());
        if (draft == null || !java.util.Set.of("uploading", "processing").contains(draft.getStatus())) return;
        drafts.uploaded(draft.getId());
    }
}

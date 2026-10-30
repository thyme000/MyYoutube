package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.mq.message.MediaUploadEventMessage;
import cn.diaojiahong.myyoutube.transcode.config.MediaProperties;
import cn.diaojiahong.myyoutube.transcode.controller.InternalStorageEventController;
import cn.diaojiahong.myyoutube.transcode.mq.listener.MediaUploadEventListener;
import cn.diaojiahong.myyoutube.video.api.AudioUploadPort;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadNotificationRoutingTest {
    @Test
    void audioAndCoverEventsReachTheAudioPortAndVideoStillUsesItsDraft() {
        MediaDraftPort drafts = mock(MediaDraftPort.class);
        AudioUploadPort audios = mock(AudioUploadPort.class);
        var consumer = new MediaUploadEventListener(drafts, audios);
        for (String key : new String[]{"uploads/audio/9/a", "uploads/audio-cover/9/c"}) {
            consumer.onMessage(MediaUploadEventMessage.builder().objectKey(key).fileSize(-100L).build());
            verify(audios).uploaded(key);
        }
        verifyNoInteractions(drafts);
        when(drafts.getByObjectKey("uploads/video/9/v")).thenReturn(new MediaDraftPort.DraftInfo(
                1L,
                9L,
                "uploads/video/9/v",
                "uploading",
                null));
        consumer.onMessage(MediaUploadEventMessage.builder().objectKey("uploads/video/9/v").build());
        verify(drafts).uploaded(1L);
    }

    @Test
    void bridgeIncludesOnlyTheRegisteredUploadDirectoriesAndChecksItsToken() throws Exception {
        var properties = new MediaProperties();
        properties.setWebhookToken("test-only");
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        var bridge = new InternalStorageEventController(properties, mq);
        JsonNode body = new ObjectMapper().readTree("""
                {"Records":[
                  {"eventName":"s3:ObjectCreated:Put","s3":{"object":{"key":"uploads/audio/9/a","size":10}}},
                  {"eventName":"s3:ObjectCreated:Put","s3":{"object":{"key":"uploads/audio-cover/9/c","size":20}}},
                  {"eventName":"s3:ObjectCreated:Put","s3":{"object":{"key":"uploads/picture/9/p","size":30}}}
                ]}
                """);
        assertThrows(BizException.class, () -> bridge.onStorageEvent("wrong", body));
        verifyNoInteractions(mq);
        bridge.onStorageEvent("test-only", body);
        verify(mq, times(2)).syncSend(anyString(), any(Message.class));
    }
}

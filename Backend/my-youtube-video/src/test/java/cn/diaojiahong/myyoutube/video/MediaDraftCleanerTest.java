package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import cn.diaojiahong.myyoutube.video.schedule.MediaDraftCleaner;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;

class MediaDraftCleanerTest {
    final MediaDraftMapper rows = mock(MediaDraftMapper.class);
    final S3StorageUtil storage = mock(S3StorageUtil.class);
    final RocketMQTemplate mq = mock(RocketMQTemplate.class);
    final MediaDraftCleaner cleaner = new MediaDraftCleaner(rows, storage, mq, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    MediaDraft uploading() {
        return new MediaDraft().setId(1L).setUserId(9L).setPreparationId(101L).setObjectKey("uploads/video/9/a.mp4")
                .setStatus(MediaDraftStatusEnum.UPLOADING).setUpdateTime(LocalDateTime.now().minusHours(8));
    }
    @Test void storageFailureCannotExpireAnUploadingDraft() {
        when(rows.selectList(any())).thenReturn(List.of(uploading()));
        when(storage.objectExists(anyString())).thenThrow(S3Exception.builder().statusCode(503).build());
        cleaner.clean(); verifyNoInteractions(mq); verify(rows, never()).update(any(), any());
    }
    @Test void newUploadingDraftDoesNotExpireAtSixHoursWhenObjectIsAbsent() {
        when(rows.selectList(any())).thenReturn(List.of(uploading()));
        when(storage.objectExists(anyString())).thenReturn(false);
        cleaner.clean(); verifyNoInteractions(mq); verify(rows, never()).update(any(), any());
    }
    @Test void completedUploadRepairsEventWithoutDeletingMedia() {
        when(rows.selectList(any())).thenReturn(List.of(uploading()));
        when(storage.objectExists(anyString())).thenReturn(true);
        cleaner.clean();
        verify(mq).syncSend(eq(cn.diaojiahong.myyoutube.common.constants.MqConstants.TOPIC_MEDIA_UPLOAD_EVENT),
                argThat((cn.diaojiahong.myyoutube.common.mq.message.MediaUploadEventMessage message) ->
                        message.getDraftId().equals(1L) && message.getUserId().equals(9L)));
        verify(storage, never()).deleteObjectQuietly(anyString());
        verify(rows, never()).update(any(), any());
    }
}

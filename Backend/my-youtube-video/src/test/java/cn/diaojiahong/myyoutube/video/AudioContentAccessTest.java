package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.AudioContentAccess;
import cn.diaojiahong.myyoutube.content.service.AfterCommitNotifications;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewStatus;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioContentAccessTest {
    private final AudioMapper audios = mock(AudioMapper.class);
    private final RocketMQTemplate mq = mock(RocketMQTemplate.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AudioContentAccess access = new AudioContentAccess(audios, new AfterCommitNotifications(mq, metrics));

    @Test void listChecksDeduplicateAudioIdsAndNotificationFailureCannotExposeBlockedContents() {
        var one = new Content().setId(1L).setType(ContentTypeEnum.DYNAMIC).setAudioId(20L);
        var two = new Content().setId(2L).setType(ContentTypeEnum.VIDEO).setAudioId(20L);
        when(audios.selectBatchIds(List.of(20L))).thenReturn(List.of(new Audio().setId(20L).setIndexVersion(3L).setReviewStatus(AudioReviewStatus.BANNED)));
        doThrow(new IllegalStateException("MQ unavailable")).when(mq).asyncSend(anyString(), any(AudioContentBanMessage.class), any(SendCallback.class));
        assertEquals(Set.of(1L, 2L), access.blocked(List.of(one, two, one)));
        verify(audios).selectBatchIds(List.of(20L));
        assertEquals(2, metrics.counter(MetricNames.AUDIO_CASCADE_NOTIFICATION_FAILED).count());
    }

    @Test void privateAndSoftDeletedAudioDoNotInvalidateHistoricalReferences() {
        when(audios.selectBatchIds(List.of(20L))).thenReturn(List.of(new Audio().setId(20L).setIsDelete(1).setVisibility("private").setReviewStatus(AudioReviewStatus.APPROVED)));
        assertTrue(access.blocked(List.of(new Content().setId(1L).setType(ContentTypeEnum.DYNAMIC).setAudioId(20L))).isEmpty());
        verifyNoInteractions(mq);
    }

    @Test void contentsWithoutAudioReferencesDoNotQueryTheAudioLibrary() {
        assertTrue(access.blocked(List.of(new Content().setId(1L).setType(ContentTypeEnum.VIDEO))).isEmpty());
        verifyNoInteractions(audios, mq);
    }

    @Test void missingAudioIsBlockedButOnlyConfirmedBansTriggerNotification() {
        Content banned = new Content().setId(1L).setAudioId(20L);
        Content missing = new Content().setId(2L).setAudioId(30L);
        when(audios.selectBatchIds(anyList())).thenReturn(List.of(new Audio().setId(20L)
                .setIndexVersion(1L).setReviewStatus(AudioReviewStatus.BANNED)));
        assertEquals(Set.of(1L, 2L), access.blocked(List.of(banned, missing)));
        verify(mq, times(1)).asyncSend(anyString(), any(AudioContentBanMessage.class), any(SendCallback.class));
        verify(audios, times(1)).selectBatchIds(anyList());
    }

    @Test void alreadyBannedManagementRecordsDoNotRepeatTheCascadeNotification() {
        assertTrue(access.blocked(List.of(new Content().setId(1L).setType(ContentTypeEnum.DYNAMIC).setAudioId(20L).setStatus(ContentStatusEnum.BANNED))).isEmpty());
        verifyNoInteractions(audios, mq);
    }
}

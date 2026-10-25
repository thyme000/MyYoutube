package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts.View;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.transcode.api.AudioPreparationPort;
import cn.diaojiahong.myyoutube.video.config.AudioProcessingProperties;
import cn.diaojiahong.myyoutube.video.domain.enums.*;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioPreparationBudgetTest {
    final AudioMapper rows = mock(AudioMapper.class);
    final AudioPreparationPort tasks = mock(AudioPreparationPort.class);
    final AudioProcessingProperties properties = new AudioProcessingProperties();
    final AtomicLong clock = new AtomicLong();
    final AudioPreparationService service = new AudioPreparationService(rows, tasks, properties, clock::get);

    @BeforeEach void setup() {
        properties.validate();
        when(rows.selectList(any())).thenReturn(List.of(audio(1), audio(2)));
        when(rows.update(isNull(), any())).thenReturn(1);
    }
    Audio audio(long id) {
        return new Audio().setId(id).setUploaderId(9L).setSourceObjectKey("uploads/audio/9/day/" + id + ".wav")
                .setProcessingStatus(AudioProcessingStatus.PROCESSING).setReviewStatus(AudioReviewStatus.PENDING).setIsDelete(0)
                .setPreparationAttempt(0).setPreparationRetry(false).setPreparationAfter(LocalDateTime.now().minusSeconds(1))
                .setProcessingDeadline(LocalDateTime.now().plusMinutes(30));
    }
    @Test void stopsClaimingFurtherRowsWhenRemainingBudgetCannotCoverOneItem() {
        when(tasks.preparation(1L)).thenAnswer(c -> {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1500));
            return new View(1L, MediaTaskStatus.RUNNING, 1, 0, null, null, null);
        });
        service.reconcile();
        verify(tasks).preparation(1L); verifyNoMoreInteractions(tasks); verify(rows, times(1)).update(isNull(), any());
    }
    @Test void slowLookupDoesNotStartAnotherRpcAfterBudgetRunsLow() {
        when(tasks.preparation(1L)).thenAnswer(c -> { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(4000)); return null; });
        service.reconcile();
        verify(tasks).preparation(1L); verifyNoMoreInteractions(tasks);
    }
    @Test void exhaustedBudgetAfterDatabaseReadDoesNotClaimOrCallAnything() {
        when(rows.selectList(any())).thenAnswer(c -> { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(5001)); return List.of(audio(1)); });
        service.reconcile();
        verify(rows, never()).update(isNull(), any()); verifyNoInteractions(tasks);
    }
    @Test void dependencyFailureStopsRoundRatherThanCallingEveryRow() {
        when(tasks.preparation(1L)).thenThrow(new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "unavailable", null));
        service.reconcile();
        verify(tasks).preparation(1L); verifyNoMoreInteractions(tasks); verify(rows, times(1)).update(isNull(), any());
    }
}

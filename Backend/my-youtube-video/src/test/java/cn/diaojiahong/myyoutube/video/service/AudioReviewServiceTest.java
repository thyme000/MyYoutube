package cn.diaojiahong.myyoutube.video.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.video.domain.enums.*;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.mapper.AudioReviewRecordMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class AudioReviewServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"FAIL", "DEAD", "SCREENED", "MANUAL"})
    void unsuccessfulUpdateDoesNotCreateAnAuditRecord(String operation) {
        var rows = mock(AudioMapper.class);
        var records = mock(AudioReviewRecordMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service = new AudioReviewService(rows, records, new TransactionTemplate(manager));
        var audio = new Audio().setId(1L).setIsDelete(0).setProcessingStatus(AudioProcessingStatus.READY)
                .setReviewStatus(AudioReviewStatus.PENDING).setAnalysisStatus(AudioAnalysisStatus.MANUAL)
                .setAnalysisAttempt(1).setPreparationAfter(LocalDateTime.now()).setIndexVersion(1L)
                .setObjectKey("media-prepared/9/1/audio-source").setProcessingDeadline(LocalDateTime.now().plusMinutes(1));
        when(rows.selectOne(any())).thenReturn(audio);
        if (operation.equals("FAIL")) service.fail(audio, "音频处理异常，请重试");
        else if (operation.equals("MANUAL")) assertFalse(service.decide(1L, AudioReviewDecision.PASS, "通过", 1L));
        else {
            var request = new AudioAnalysisContracts.Request(1L, 9L, "音频", audio.getObjectKey(), 1234,
                    MediaTaskType.AUDIO_REVIEW, "audio-artifacts/9/1/asr/result.json");
            var result = new AudioAnalysisContracts.ScreenResult(1L, audio.getObjectKey(), request.transcriptKey(), 0, true, false, List.of());
            var status = operation.equals("DEAD") ? MediaTaskStatus.DEAD : MediaTaskStatus.SUCCESS;
            service.accept(audio, new AudioAnalysisContracts.View(1L, request.type(), status, 1, JsonUtil.toJson(result), null), request);
        }
        verify(rows).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoInteractions(records);
    }
}

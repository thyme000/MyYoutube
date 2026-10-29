package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.video.domain.enums.*;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AudioReviewService {
    private static final int DECISION_TIMEOUT_SECONDS = 3;
    private final AudioMapper rows;
    private final AudioReviewRecordMapper records;
    private final TransactionTemplate transaction;

    public void accept(Audio audio, AudioAnalysisContracts.View view, AudioAnalysisContracts.Request request) {
        transaction.executeWithoutResult(tx -> {
            var current = lock(audio.getId());
            if (!active(current, audio) || view.attempt() < current.getAnalysisAttempt()
                    || current.getProcessingDeadline() == null || !current.getProcessingDeadline().isAfter(LocalDateTime.now())) return;
            var update = AudioIndexChanges.bump(fenced(audio)).set(Audio::getPreparationRetry, false);
            if (view.status() == MediaTaskStatus.DEAD) {
                String reason = "音频识别或初筛失败，可重试或转人工处理";
                if (rows.update(null, update.set(Audio::getAnalysisStatus, AudioAnalysisStatus.FAILED).set(Audio::getPreparationAfter, null)
                        .set(Audio::getProcessingDeadline, null).set(Audio::getAnalysisAttempt, view.attempt()).set(Audio::getErrorMessage, reason)) != 1) return;
                record(audio, "TECHNICAL", "ERROR", reason, view.failureCode(), view.attempt(), null);
            } else if (view.status() == MediaTaskStatus.SUCCESS) {
                if (request.type() == MediaTaskType.AUDIO_ASR) {
                    var result = JsonUtil.fromJson(view.resultJson(), AudioAnalysisContracts.TranscriptResult.class); result.validate(request);
                    rows.update(null, update.set(Audio::getTranscriptKey, result.transcriptKey()).set(Audio::getAnalysisAttempt, 0)
                            .set(Audio::getAnalysisStatus, AudioAnalysisStatus.SCREENING).set(Audio::getPreparationAfter, LocalDateTime.now()));
                } else {
                    var result = JsonUtil.fromJson(view.resultJson(), AudioAnalysisContracts.ScreenResult.class); result.validate(request);
                    if (rows.update(null, update.set(Audio::getMachineResultJson, view.resultJson()).set(Audio::getAnalysisAttempt, view.attempt())
                            .set(Audio::getAnalysisStatus, AudioAnalysisStatus.MANUAL).set(Audio::getPreparationAfter, null).set(Audio::getProcessingDeadline, null)) != 1) return;
                    record(audio, "AUTO", result.localBlocked() ? "RULE_HIT" : result.modelSkipped() ? "SKIPPED" : "SCREENED",
                            result.modelSkipped() ? "有效识别文字不足，跳过模型，等待人工审核" : "机器初筛完成，等待人工审核", view.resultJson(), view.attempt(), null);
                }
            }
        });
    }
    public void fail(Audio audio, String reason) {
        transaction.executeWithoutResult(tx -> {
            if (!active(lock(audio.getId()), audio)) return;
            if (rows.update(null, AudioIndexChanges.bump(fenced(audio)).set(Audio::getAnalysisStatus, AudioAnalysisStatus.FAILED).set(Audio::getErrorMessage, reason)
                    .set(Audio::getPreparationAfter, null).set(Audio::getProcessingDeadline, null).set(Audio::getPreparationRetry, false)) != 1) return;
            record(audio, "TECHNICAL", "ERROR", reason, null, audio.getAnalysisAttempt(), null);
        });
    }
    public boolean decide(Long id, AudioReviewDecision decision, String reason, Long actor) {
        if (actor == null || actor <= 0 || decision == null || reason == null || reason.isBlank() || reason.length() > 4000)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "审核决定或理由无效");
        // 独立模板只限制人工决定的数据库事务，不改变后台识别所用模板或连接池会话参数。
        var decisionTransaction = new TransactionTemplate(Objects.requireNonNull(transaction.getTransactionManager()), transaction);
        decisionTransaction.setTimeout(DECISION_TIMEOUT_SECONDS);
        try {
            return Boolean.TRUE.equals(decisionTransaction.execute(tx -> decideLocked(id, decision, reason, actor)));
        } catch (CannotAcquireLockException | QueryTimeoutException | TransactionTimedOutException busy) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "音频审核暂时繁忙，本次操作未完成，请稍后重试", busy);
        }
    }

    private boolean decideLocked(Long id, AudioReviewDecision decision, String reason, Long actor) {
        Audio audio = lock(id);
        if (audio == null || audio.getIsDelete() != 0) throw new BizException(ErrorCode.NOT_FOUND, "音频不存在");
        boolean ban = decision == AudioReviewDecision.BAN;
        if (audio.getReviewStatus() == AudioReviewStatus.BANNED || !ban && (audio.getReviewStatus() != AudioReviewStatus.PENDING
                || audio.getProcessingStatus() != AudioProcessingStatus.READY
                || audio.getAnalysisStatus() != AudioAnalysisStatus.MANUAL && audio.getAnalysisStatus() != AudioAnalysisStatus.FAILED))
            throw new BizException(ErrorCode.STATE_CONFLICT, "音频尚未进入人工审核或已有终审结论");
        AudioReviewStatus status = switch (decision) {
            case PASS -> AudioReviewStatus.APPROVED;
            case REJECT -> AudioReviewStatus.REJECTED;
            case BAN -> AudioReviewStatus.BANNED;
        };
        LambdaUpdateWrapper<Audio> update = AudioIndexChanges.bump(new LambdaUpdateWrapper<Audio>().eq(Audio::getId, id))
                .set(Audio::getReviewStatus, status).set(Audio::getReviewReason, reason.trim()).set(Audio::getErrorMessage, null).set(Audio::getPreparationAfter, null)
                .set(Audio::getProcessingDeadline, null).set(Audio::getPreparationRetry, false);
        if (ban && (audio.getProcessingStatus() == AudioProcessingStatus.UPLOADING || audio.getProcessingStatus() == AudioProcessingStatus.EXPIRED))
            update.set(Audio::getProcessingStatus, AudioProcessingStatus.EXPIRED)
                    .set(Audio::getUploadAfter, audio.getUploadDeadline().plusHours(24));
        if (rows.update(null, update) != 1) return false;
        record(audio, "MANUAL", decision.name(), reason.trim(), null, audio.getAnalysisAttempt(), actor);
        return true;
    }
    private Audio lock(Long id) { return rows.selectOne(new LambdaQueryWrapper<Audio>().eq(Audio::getId, id).last("FOR UPDATE")); }
    private boolean active(Audio current, Audio expected) {
        return current != null && current.getIsDelete() == 0 && current.getReviewStatus() == AudioReviewStatus.PENDING
                && current.getProcessingStatus() == AudioProcessingStatus.READY && current.getAnalysisStatus() == expected.getAnalysisStatus()
                && Objects.equals(current.getPreparationAfter(), expected.getPreparationAfter())
                && Objects.equals(current.getAnalysisAttempt(), expected.getAnalysisAttempt()) && Objects.equals(current.getObjectKey(), expected.getObjectKey());
    }
    public static LambdaUpdateWrapper<Audio> fenced(Audio audio) {
        return new LambdaUpdateWrapper<Audio>().eq(Audio::getId, audio.getId()).eq(Audio::getReviewStatus, AudioReviewStatus.PENDING)
                .eq(Audio::getIsDelete, 0).eq(Audio::getProcessingStatus, AudioProcessingStatus.READY)
                .eq(audio.getAnalysisStatus() != null, Audio::getAnalysisStatus, audio.getAnalysisStatus())
                .isNull(audio.getAnalysisStatus() == null, Audio::getAnalysisStatus)
                .eq(Audio::getAnalysisAttempt, audio.getAnalysisAttempt())
                .eq(audio.getPreparationAfter() != null, Audio::getPreparationAfter, audio.getPreparationAfter())
                .isNull(audio.getPreparationAfter() == null, Audio::getPreparationAfter);
    }
    private void record(Audio audio, String source, String decision, String reason, String payload, Integer attempt, Long actor) {
        records.insert(new AudioReviewRecord().setAudioId(audio.getId()).setSource(source).setDecision(decision).setReason(reason)
                .setPayloadJson(payload).setAttempt(attempt).setActorId(actor).setCreateTime(LocalDateTime.now()));
    }
}

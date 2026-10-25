package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts.*;
import cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.transcode.api.AudioPreparationPort;
import cn.diaojiahong.myyoutube.video.config.AudioProcessingProperties;
import cn.diaojiahong.myyoutube.video.domain.enums.*;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Slf4j
@Service
public class AudioPreparationService {
    private final AudioMapper rows;
    private final AudioPreparationPort tasks;
    private final AudioProcessingProperties properties;
    private final LongSupplier ticker;

    @Autowired
    public AudioPreparationService(AudioMapper rows, AudioPreparationPort tasks, AudioProcessingProperties properties) {
        this(rows, tasks, properties, System::nanoTime);
    }

    AudioPreparationService(AudioMapper rows, AudioPreparationPort tasks, AudioProcessingProperties properties, LongSupplier ticker) {
        this.rows = rows; this.tasks = tasks; this.properties = properties; this.ticker = ticker;
    }

    @Scheduled(fixedDelayString = "#{@audioProcessingProperties.scanIntervalMs}", initialDelayString = "#{@audioProcessingProperties.scanIntervalMs}")
    public void reconcile() {
        long deadline = ticker.getAsLong() + TimeUnit.MILLISECONDS.toNanos(properties.getRoundBudgetMs());
        var now = LocalDateTime.now();
        var page = rows.selectList(new LambdaQueryWrapper<Audio>().eq(Audio::getProcessingStatus, AudioProcessingStatus.PROCESSING)
                .eq(Audio::getReviewStatus, AudioReviewStatus.PENDING).eq(Audio::getIsDelete, 0).le(Audio::getPreparationAfter, now)
                .orderByAsc(Audio::getPreparationAfter).orderByAsc(Audio::getId).last("LIMIT " + properties.getBatchSize()));
        for (var audio : page) {
            if (!hasBudget(deadline, properties.itemBudgetMs())) return;
            var lease = LocalDateTime.now().plusSeconds(properties.getLeaseSeconds()).withNano(0);
            if (rows.update(null, current(audio).eq(Audio::getPreparationAfter, audio.getPreparationAfter())
                    .set(Audio::getPreparationAfter, lease)) == 0) continue;
            audio.setPreparationAfter(lease);
            try { advance(audio, deadline); }
            catch (Exception e) {
                if (e instanceof DependencyException || e instanceof RetryableException
                        || e instanceof FeignException remote && (remote.status() == 429 || remote.status() >= 500))
                    log.warn("Audio preparation reconciliation deferred audioId={} reason={}", audio.getId(), e.getClass().getSimpleName());
                else log.error("Audio preparation reconciliation failed audioId={}", audio.getId(), e);
                return;
            }
        }
    }

    private void advance(Audio audio, long deadline) {
        if (audio.getProcessingDeadline() == null || !audio.getProcessingDeadline().isAfter(LocalDateTime.now())) {
            fail(audio, "音频处理超时，请重试");
            return;
        }
        if (!hasBudget(deadline, properties.callBudgetMs())) return;
        var view = tasks.preparation(audio.getId());
        var request = new Request(audio.getId(), audio.getUploaderId(), audio.getSourceObjectKey(), audio.getSourceCoverObjectKey());
        if (view == null) {
            if (hasBudget(deadline, properties.callBudgetMs())) tasks.prepare(request);
            return;
        }
        if (!Objects.equals(view.audioId(), audio.getId())) throw new IllegalStateException("Audio preparation identity mismatch");
        if (view.attempt() < audio.getPreparationAttempt()) return;
        if (Boolean.TRUE.equals(audio.getPreparationRetry()) && view.status() != MediaTaskStatus.SUCCESS) {
            if (!hasBudget(deadline, properties.callBudgetMs())) return;
            if (!tasks.retry(audio.getId())) {
                fail(audio, "未能启动音频重试，请稍后重试");
                return;
            }
            rows.update(null, leased(audio).set(Audio::getPreparationRetry, false));
            return;
        }
        if (view.status() == MediaTaskStatus.SUCCESS) {
            var result = Objects.requireNonNull(view.result(), "Missing audio preparation result");
            result.validate(request);
            int updated = rows.update(null, leased(audio).gt(Audio::getProcessingDeadline, LocalDateTime.now())
                    .set(Audio::getProcessingStatus, AudioProcessingStatus.READY).set(Audio::getObjectKey, result.objectKey())
                    .set(Audio::getCoverObjectKey, result.coverObjectKey()).set(Audio::getDurationMs, result.durationMs())
                    .set(Audio::getFileSize, result.bytes()).set(Audio::getMime, result.mime()).set(Audio::getPreparationAttempt, view.attempt())
                    .set(Audio::getPreparationRetry, false).set(Audio::getPreparationAfter, null)
                    .set(Audio::getProcessingDeadline, null).set(Audio::getErrorMessage, null));
            if (updated > 0) log.info("Audio prepared audioId={} attempt={} durationMs={} bytes={}", audio.getId(), view.attempt(), result.durationMs(), result.bytes());
        } else if (view.status() == MediaTaskStatus.DEAD) {
            rows.update(null, leased(audio).set(Audio::getProcessingStatus, AudioProcessingStatus.FAILED)
                    .set(Audio::getPreparationAttempt, view.attempt()).set(Audio::getPreparationAfter, null)
                    .set(Audio::getProcessingDeadline, null).set(Audio::getErrorMessage, InvalidMediaInputException.publicMessage(view.failureCode())));
        }
    }

    private boolean hasBudget(long deadline, long requiredMs) {
        return deadline - ticker.getAsLong() >= TimeUnit.MILLISECONDS.toNanos(requiredMs);
    }

    private void fail(Audio audio, String reason) {
        rows.update(null, leased(audio).set(Audio::getProcessingStatus, AudioProcessingStatus.FAILED).set(Audio::getPreparationRetry, false)
                .set(Audio::getPreparationAfter, null).set(Audio::getProcessingDeadline, null).set(Audio::getErrorMessage, reason));
    }

    private LambdaUpdateWrapper<Audio> current(Audio audio) {
        return new LambdaUpdateWrapper<Audio>().eq(Audio::getId, audio.getId()).eq(Audio::getUploaderId, audio.getUploaderId())
                .eq(Audio::getProcessingStatus, AudioProcessingStatus.PROCESSING).eq(Audio::getReviewStatus, AudioReviewStatus.PENDING)
                .eq(Audio::getIsDelete, 0).eq(Audio::getPreparationAttempt, audio.getPreparationAttempt());
    }

    private LambdaUpdateWrapper<Audio> leased(Audio audio) { return current(audio).eq(Audio::getPreparationAfter, audio.getPreparationAfter()); }
}

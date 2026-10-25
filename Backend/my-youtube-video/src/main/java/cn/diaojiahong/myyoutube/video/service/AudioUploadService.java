package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.config.AudioProcessingProperties;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioUploadDTO;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioProcessingStatus;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewStatus;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioUploadVO;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AudioUploadService {
    public static final long UPLOAD_SECONDS = 1800;
    public static final long GRACE_SECONDS = 600;
    private static final Duration STORAGE_TIMEOUT = Duration.ofSeconds(1);
    private final IAudioService audios;
    private final AudioMapper rows;
    private final S3StorageUtil storage;
    private final AudioProcessingProperties processing;

    public AudioUploadVO start(AudioUploadDTO request) {
        long id = Long.parseLong(audios.registerUpload(request));
        var audio = rows.selectById(id);
        if (audio.getReviewStatus() == AudioReviewStatus.BANNED)
            throw new BizException(ErrorCode.STATE_CONFLICT, "该音频已不可用");
        var expires = audio.getUploadDeadline().minusSeconds(GRACE_SECONDS);
        if (audio.getProcessingStatus() == AudioProcessingStatus.EXPIRED)
            throw new BizException(ErrorCode.STATE_CONFLICT, "本次上传已失效，请重新上传");
        if (audio.getProcessingStatus() != AudioProcessingStatus.UPLOADING)
            return new AudioUploadVO(Long.toString(id), audio.getProcessingStatus().getCode(), expires, null, null);
        long seconds = Math.min(UPLOAD_SECONDS, Duration.between(LocalDateTime.now(), expires).getSeconds());
        if (seconds < 1) throw new BizException(ErrorCode.STATE_CONFLICT, "上传地址已过期，请重新上传");
        return new AudioUploadVO(Long.toString(id), audio.getProcessingStatus().getCode(), expires,
                sign(audio.getSourceObjectKey(), audio.getUploadSize(), audio.getUploadContentType(), seconds),
                audio.getSourceCoverObjectKey() == null ? null
                        : sign(audio.getSourceCoverObjectKey(), audio.getCoverUploadSize(), audio.getCoverUploadContentType(), seconds));
    }

    private AudioUploadVO.File sign(String key, long bytes, String type, long seconds) {
        return new AudioUploadVO.File(key, storage.presignPutUrl(key, seconds, bytes, type), "put", bytes, type);
    }

    public void complete(Long id) {
        String user = UserContext.getUserId();
        if (user == null) throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        var audio = rows.selectById(id);
        if (audio == null || audio.getIsDelete() != 0 || !Objects.equals(audio.getUploaderId(), Long.valueOf(user)))
            throw new BizException(ErrorCode.NOT_FOUND, "音频不存在");
        advance(audio);
    }

    public void uploaded(String key) {
        if (key == null || !(key.startsWith("uploads/audio/") || key.startsWith("uploads/audio-cover/"))) return;
        var audio = rows.selectOne(new LambdaQueryWrapper<Audio>().eq(
                key.startsWith("uploads/audio/") ? Audio::getSourceObjectKey : Audio::getSourceCoverObjectKey, key));
        if (audio != null) advance(audio);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void reconcile() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        var page = rows.selectList(new LambdaQueryWrapper<Audio>()
                .eq(Audio::getReviewStatus, AudioReviewStatus.PENDING)
                .in(Audio::getProcessingStatus, AudioProcessingStatus.UPLOADING, AudioProcessingStatus.EXPIRED)
                .le(Audio::getUploadAfter, LocalDateTime.now()).orderByAsc(Audio::getUploadAfter).orderByAsc(Audio::getId).last("LIMIT 50"));
        for (var audio : page) {
            if (deadline - System.nanoTime() < TimeUnit.SECONDS.toNanos(2)) return;
            advance(audio);
        }
    }

    private void advance(Audio audio) {
        if (audio.getUploadAfter() == null || audio.getUploadAfter().isAfter(LocalDateTime.now())
                || audio.getReviewStatus() != AudioReviewStatus.PENDING
                || audio.getProcessingStatus() != AudioProcessingStatus.UPLOADING && audio.getProcessingStatus() != AudioProcessingStatus.EXPIRED) return;
        var lease = LocalDateTime.now().plusSeconds(10).withNano(0);
        if (rows.update(null, current(audio).eq(Audio::getUploadAfter, audio.getUploadAfter()).set(Audio::getUploadAfter, lease)) != 1) return;
        audio.setUploadAfter(lease);
        try {
            if (audio.getProcessingStatus() == AudioProcessingStatus.EXPIRED) {
                AudioPreparationContracts.upload(audio.getSourceObjectKey(), "audio", audio.getUploaderId());
                storage.deleteUpload(audio.getSourceObjectKey(), STORAGE_TIMEOUT);
                if (audio.getSourceCoverObjectKey() != null) {
                    AudioPreparationContracts.upload(audio.getSourceCoverObjectKey(), "audio-cover", audio.getUploaderId());
                    storage.deleteUpload(audio.getSourceCoverObjectKey(), STORAGE_TIMEOUT);
                }
                rows.update(null, leased(audio).set(Audio::getUploadAfter, null));
                return;
            }
            if (audio.getIsDelete() != 0 || !audio.getUploadDeadline().isAfter(LocalDateTime.now())) {
                expire(audio, "上传未完成或已取消，请重新上传");
                return;
            }
            var source = storage.inspectUpload(audio.getSourceObjectKey(), STORAGE_TIMEOUT);
            var cover = audio.getSourceCoverObjectKey() == null ? null : storage.inspectUpload(audio.getSourceCoverObjectKey(), STORAGE_TIMEOUT);
            if (source == null || audio.getSourceCoverObjectKey() != null && cover == null) return;
            if (!matches(source, audio.getUploadSize(), audio.getUploadContentType())
                    || cover != null && !matches(cover, audio.getCoverUploadSize(), audio.getCoverUploadContentType())) {
                expire(audio, "上传文件与登记信息不一致，本次上传已失效，请重新上传");
                return;
            }
            rows.update(null, AudioIndexChanges.bump(leased(audio)).eq(Audio::getIsDelete, 0).gt(Audio::getUploadDeadline, LocalDateTime.now())
                    .set(Audio::getProcessingStatus, AudioProcessingStatus.PROCESSING).set(Audio::getUploadAfter, null)
                    .set(Audio::getPreparationAfter, LocalDateTime.now())
                    .set(Audio::getProcessingDeadline, LocalDateTime.now().plusMinutes(processing.getDeadlineMinutes())));
        } catch (Exception error) {
            log.warn("Audio upload reconciliation deferred audioId={} reason={}", audio.getId(), error.getClass().getSimpleName());
        }
    }

    private boolean matches(S3StorageUtil.UploadMetadata metadata, Long bytes, String type) {
        return Objects.equals(metadata.bytes(), bytes) && Objects.equals(metadata.contentType(), type);
    }

    private void expire(Audio audio, String reason) {
        rows.update(null, AudioIndexChanges.bump(leased(audio)).set(Audio::getProcessingStatus, AudioProcessingStatus.EXPIRED)
                .set(Audio::getErrorMessage, reason).set(Audio::getUploadAfter, audio.getUploadDeadline().plusHours(24)));
    }

    private LambdaUpdateWrapper<Audio> current(Audio audio) {
        return new LambdaUpdateWrapper<Audio>().eq(Audio::getId, audio.getId()).eq(Audio::getReviewStatus, AudioReviewStatus.PENDING)
                .eq(Audio::getProcessingStatus, audio.getProcessingStatus());
    }
    private LambdaUpdateWrapper<Audio> leased(Audio audio) { return current(audio).eq(Audio::getUploadAfter, audio.getUploadAfter()); }
}

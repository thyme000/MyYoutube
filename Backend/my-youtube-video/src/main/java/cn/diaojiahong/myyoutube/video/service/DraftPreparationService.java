package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.content.service.DraftPublicationNotifications;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.video.api.enums.*;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class DraftPreparationService {
    private final MediaDraftMapper drafts;
    private final MediaTaskPort tasks;
    private final DraftObservation observation;
    private final DraftPublicationNotifications publicationNotifications;

    public boolean uploaded(Long id) {
        int hit = observation.update(id, new LambdaUpdateWrapper<MediaDraft>().eq(MediaDraft::getId, id)
                .eq(MediaDraft::getType, ContentTypeEnum.VIDEO).eq(MediaDraft::getStatus, MediaDraftStatusEnum.UPLOADING)
                .set(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                .set(MediaDraft::getPreparationAfter, LocalDateTime.now()));
        if (hit > 0) log.info("Draft upload confirmed draftId={} status=PROCESSING", id);
        else log.debug("Draft upload event ignored draftId={} reason=NOT_UPLOADING", id);
        return hit > 0;
    }

    @ObservedBusiness(BusinessOperation.RETRY_PREPARATION)
    public boolean retry(Long id, Long userId) {
        var draft = drafts.selectById(id);
        if (draft == null || !userId.equals(draft.getUserId())) throw new BizException(ErrorCode.NOT_FOUND, "草稿不存在");
        if (draft.expiredAt(LocalDateTime.now())) return false;
        if (draft.getStatus() == MediaDraftStatusEnum.PROCESSING) {
            log.debug("Draft retry ignored draftId={} reason=STILL_PROCESSING", id);
            return true;
        }
        if (draft.getStatus() != MediaDraftStatusEnum.FAILED) {
            log.debug("Draft retry rejected draftId={} reason=STATUS_{}", id, draft.getStatus());
            return false;
        }
        var result = tasks.preparation(draft.getPreparationId());
        if (result == null || !Objects.equals(result.preparationId(), draft.getPreparationId())
                || result.attempt() < draft.getPreparationAttempt())
            throw new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "无法确认媒体准备状态，请稍后重试", null);
        var update = version(draft).eq(MediaDraft::getStatus, MediaDraftStatusEnum.FAILED)
                .set(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                .set(MediaDraft::getPreparationAfter, LocalDateTime.now()).set(MediaDraft::getErrorMessage, null);
        if (result.parametersChanged()) {
            // 参数指纹变了: 旧准备清单不能继续用, 换 preparationId 与媒体版本重做
            long nextPreparationId = IdWorker.getId();
            log.info("Draft retry re-prepares media draftId={} reason=PARAMETERS_CHANGED previousPreparationId={} nextPreparationId={} mediaVersion={}",
                    id, draft.getPreparationId(), nextPreparationId, draft.getMediaVersion() + 1);
            update.eq(MediaDraft::getRevision, draft.getRevision()).setIncrBy(MediaDraft::getRevision, 1)
                    .set(MediaDraft::getMediaVersion, draft.getMediaVersion() + 1)
                    .set(MediaDraft::getPreparationId, nextPreparationId).set(MediaDraft::getPreparationAttempt, 0)
                    .set(MediaDraft::getPreparationRetry, false).set(MediaDraft::getPreparedJson, null)
                    .set(MediaDraft::getPreparedCoverObjectKey, null).set(MediaDraft::getExpiresAt, LocalDateTime.now().plusDays(7));
            if (Objects.equals(draft.getSubmissionRevision(), draft.getRevision()))
                update.set(MediaDraft::getSubmissionRevision, draft.getRevision() + 1);
        } else {
            log.info("Draft retry reuses preparation draftId={} preparationId={} nextAttempt={} reason=TRANSIENT_FAILURE",
                    id, draft.getPreparationId(), draft.getPreparationAttempt() + 1);
            update.set(MediaDraft::getPreparationRetry, true)
                    .set(MediaDraft::getPreparationAttempt, draft.getPreparationAttempt() + 1);
        }
        return observation.update(id, update) > 0;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void reconcile() {
        long cursor = 0;
        var now = LocalDateTime.now();
        while (true) {
            var page = drafts.selectList(new LambdaQueryWrapper<MediaDraft>().gt(MediaDraft::getId, cursor)
                    .eq(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING).le(MediaDraft::getPreparationAfter, now)
                    .orderByAsc(MediaDraft::getId).last("LIMIT 100"));
            if (page.isEmpty()) return;
            cursor = page.get(page.size() - 1).getId();
            for (var draft : page) {
                if (drafts.update(null, version(draft).eq(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                        .le(MediaDraft::getPreparationAfter, now).set(MediaDraft::getPreparationAfter, now.plusSeconds(15))) == 0) continue;
                try { advance(draft); }
                catch (Exception e) { log.warn("Draft preparation reconciliation failed draftId={} mediaVersion={}", draft.getId(), draft.getMediaVersion(), e); }
            }
        }
    }

    private void advance(MediaDraft draft) {
        var media = draft.getType() == ContentTypeEnum.VIDEO
                ? new ContentMedia("video", new VideoMedia(draft.getObjectKey(), 0), null)
                : new ContentMedia("dynamic", null, JsonUtil.fromJson(draft.getMediaJson(), DynamicMedia.class));
        var request = new Request(draft.getPreparationId(), draft.getId(), draft.getMediaVersion(), draft.getUserId(), media, draft.getCoverObjectKey());
        tasks.prepare(request);
        if (Boolean.TRUE.equals(draft.getPreparationRetry())) {
            if (!tasks.retryPreparation(draft.getPreparationId())) throw new IllegalStateException("Preparation retry rejected");
            drafts.update(null, version(draft).eq(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                    .set(MediaDraft::getPreparationRetry, false));
        }
        var result = tasks.preparation(draft.getPreparationId());
        if (result == null || !Objects.equals(result.preparationId(), draft.getPreparationId())
                || result.attempt() < draft.getPreparationAttempt()) {
            log.debug("Draft preparation reconciliation deferred draftId={} preparationId={} reason=STALE_RESULT",
                    draft.getId(), draft.getPreparationId());
            return;
        }
        if (MediaTaskStatus.SUCCESS.equals(result.status())) {
            var frozen = PreparedMedia.freeze(request, result.manifest());
            int changed = observation.update(draft.getId(), version(draft).eq(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                    .set(MediaDraft::getStatus, MediaDraftStatusEnum.READY).set(MediaDraft::getPreparedJson, JsonUtil.toJson(frozen.media()))
                    .set(MediaDraft::getPreparedCoverObjectKey, frozen.coverObjectKey()).set(MediaDraft::getDurationMs, frozen.media().durationMs())
                    .set(MediaDraft::getPreparationAttempt, result.attempt()).set(MediaDraft::getErrorMessage, null));
            if (changed > 0) publicationNotifications.afterCommit(drafts.selectById(draft.getId()));
            log.info("Draft preparation ready draftId={} preparationId={} mediaVersion={} attempt={} media={} durationMs={} frames={}",
                    draft.getId(), draft.getPreparationId(), draft.getMediaVersion(), result.attempt(), frozen.media().type(),
                    frozen.media().durationMs(), frozen.media().preparation().videoFrameCount());
        } else if (MediaTaskStatus.DEAD.equals(result.status())) {
            String error = result.errorMessage() == null ? "媒体准备失败" : result.errorMessage();
            observation.update(draft.getId(), version(draft).eq(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                    .set(MediaDraft::getStatus, MediaDraftStatusEnum.FAILED).set(MediaDraft::getPreparationAttempt, result.attempt())
                    .set(MediaDraft::getErrorMessage, error.substring(0, Math.min(500, error.length()))));
            log.warn("Draft preparation failed draftId={} preparationId={} mediaVersion={} attempt={} error={}",
                    draft.getId(), draft.getPreparationId(), draft.getMediaVersion(), result.attempt(),
                    error.substring(0, Math.min(500, error.length())));
        }
    }

    private LambdaUpdateWrapper<MediaDraft> version(MediaDraft draft) {
        return new LambdaUpdateWrapper<MediaDraft>().eq(MediaDraft::getId, draft.getId()).eq(MediaDraft::getUserId, draft.getUserId())
                .eq(MediaDraft::getMediaVersion, draft.getMediaVersion()).eq(MediaDraft::getPreparationId, draft.getPreparationId())
                .eq(MediaDraft::getPreparationAttempt, draft.getPreparationAttempt());
    }
}

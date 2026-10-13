package cn.diaojiahong.myyoutube.video.service;

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

    public boolean uploaded(Long id) {
        return drafts.update(null, new LambdaUpdateWrapper<MediaDraft>().eq(MediaDraft::getId, id)
                .eq(MediaDraft::getType, ContentTypeEnum.VIDEO).eq(MediaDraft::getStatus, MediaDraftStatusEnum.UPLOADING)
                .set(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                .set(MediaDraft::getPreparationAfter, LocalDateTime.now())) > 0;
    }

    public boolean retry(Long id, Long userId) {
        var draft = drafts.selectById(id);
        if (draft == null || !userId.equals(draft.getUserId())) throw new BizException(BizException.BIZ_ERROR, "草稿不存在");
        if (draft.getStatus() == MediaDraftStatusEnum.PROCESSING) return true;
        if (draft.getStatus() != MediaDraftStatusEnum.FAILED) return false;
        var result = tasks.preparation(draft.getPreparationId());
        if (result == null || !Objects.equals(result.preparationId(), draft.getPreparationId())
                || result.attempt() < draft.getPreparationAttempt())
            throw new BizException(BizException.BIZ_ERROR, "无法确认媒体准备状态，请稍后重试");
        var update = version(draft).eq(MediaDraft::getStatus, MediaDraftStatusEnum.FAILED)
                .set(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                .set(MediaDraft::getPreparationAfter, LocalDateTime.now()).set(MediaDraft::getErrorMessage, null);
        if (result.parametersChanged()) {
            update.eq(MediaDraft::getRevision, draft.getRevision()).setIncrBy(MediaDraft::getRevision, 1)
                    .set(MediaDraft::getMediaVersion, draft.getMediaVersion() + 1)
                    .set(MediaDraft::getPreparationId, IdWorker.getId()).set(MediaDraft::getPreparationAttempt, 0)
                    .set(MediaDraft::getPreparationRetry, false).set(MediaDraft::getPreparedJson, null)
                    .set(MediaDraft::getPreparedCoverObjectKey, null).set(MediaDraft::getExpiresAt, LocalDateTime.now().plusDays(7));
        } else {
            update.set(MediaDraft::getPreparationRetry, true)
                    .set(MediaDraft::getPreparationAttempt, draft.getPreparationAttempt() + 1);
        }
        return drafts.update(null, update) > 0;
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
                || result.attempt() < draft.getPreparationAttempt()) return;
        if ("SUCCESS".equals(result.status())) {
            var frozen = PreparedMedia.freeze(request, result.manifest());
            drafts.update(null, version(draft).eq(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                    .set(MediaDraft::getStatus, MediaDraftStatusEnum.READY).set(MediaDraft::getPreparedJson, JsonUtil.toJson(frozen.media()))
                    .set(MediaDraft::getPreparedCoverObjectKey, frozen.coverObjectKey()).set(MediaDraft::getDurationMs, frozen.media().durationMs())
                    .set(MediaDraft::getPreparationAttempt, result.attempt()).set(MediaDraft::getErrorMessage, null));
        } else if ("DEAD".equals(result.status())) {
            String error = result.errorMessage() == null ? "媒体准备失败" : result.errorMessage();
            drafts.update(null, version(draft).eq(MediaDraft::getStatus, MediaDraftStatusEnum.PROCESSING)
                    .set(MediaDraft::getStatus, MediaDraftStatusEnum.FAILED).set(MediaDraft::getPreparationAttempt, result.attempt())
                    .set(MediaDraft::getErrorMessage, error.substring(0, Math.min(500, error.length()))));
        }
    }

    private LambdaUpdateWrapper<MediaDraft> version(MediaDraft draft) {
        return new LambdaUpdateWrapper<MediaDraft>().eq(MediaDraft::getId, draft.getId()).eq(MediaDraft::getUserId, draft.getUserId())
                .eq(MediaDraft::getMediaVersion, draft.getMediaVersion()).eq(MediaDraft::getPreparationId, draft.getPreparationId())
                .eq(MediaDraft::getPreparationAttempt, draft.getPreparationAttempt());
    }
}

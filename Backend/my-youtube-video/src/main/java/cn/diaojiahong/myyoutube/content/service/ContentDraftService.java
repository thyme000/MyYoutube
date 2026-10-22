package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.DynamicMedia;
import java.time.LocalDateTime;
import java.util.Objects;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.content.support.ContentSubmissionRules;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.dto.ContentMetadataDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicDraftDTO;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.domain.vo.ContentDraftVO;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import cn.diaojiahong.myyoutube.video.service.DynamicMediaPreparationService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContentDraftService {
    private final MediaDraftMapper drafts;
    private final DynamicMediaPreparationService preparation;
    private final cn.diaojiahong.myyoutube.video.service.DraftObservation observation;
    private final ContentSubmissionRules rules;

    @ObservedBusiness(BusinessOperation.SAVE_DYNAMIC)
    public ContentDraftVO saveDynamic(Long draftId, Long userId, DynamicDraftDTO dto) {
        MediaDraft current = draftId == null ? null : owned(draftId, userId);
        if (current != null && (current.getType() != ContentTypeEnum.DYNAMIC || !java.util.Set.of(MediaDraftStatusEnum.READY, MediaDraftStatusEnum.PROCESSING, MediaDraftStatusEnum.FAILED).contains(current.getStatus())))
            throw new BizException(ErrorCode.STATE_CONFLICT, "草稿不可编辑");
        if (dto == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "草稿不能为空");
        var draft = new MediaDraft().setId(draftId == null ? IdWorker.getId() : draftId).setUserId(userId)
                .setType(ContentTypeEnum.DYNAMIC).setStatus(MediaDraftStatusEnum.PROCESSING)
                .setTitle(rules.title(dto.getTitle()))
                .setDescription(rules.description(dto.getDescription()))
                .setVisibility("public")
                .setAllowComment(rules.allowComment(dto.getAllowComment()));
        var prepared = preparation.prepare(dto, userId);
        var media = prepared.media();
        draft.setMediaJson(JsonUtil.toJson(media)).setDurationMs(media.exportDurationMs())
                .setCoverObjectKey(prepared.coverObjectKey()).setRevision(current == null ? 0 : current.getRevision() + 1);
        boolean changedMedia = current == null || !Objects.equals(JsonUtil.fromJson(current.getMediaJson(), DynamicMedia.class), media)
                || !Objects.equals(current.getCoverObjectKey(), draft.getCoverObjectKey());
        draft.setMediaVersion(current == null ? 0L : current.getMediaVersion() + (changedMedia ? 1 : 0))
                .setPreparationId(changedMedia ? IdWorker.getId() : current.getPreparationId())
                .setPreparationAttempt(changedMedia ? 0 : current.getPreparationAttempt())
                .setExpiresAt(LocalDateTime.now().plusDays(7)).setPreparationRetry(false).setPreparationAfter(changedMedia ? LocalDateTime.now() : current.getPreparationAfter());
        if (!changedMedia) draft.setStatus(current.getStatus()).setPreparedJson(current.getPreparedJson())
                .setPreparedCoverObjectKey(current.getPreparedCoverObjectKey()).setErrorMessage(current.getErrorMessage());
        if (current == null) observation.insert(draft);
        else {
            var update = editable(draftId, userId).eq(MediaDraft::getType, ContentTypeEnum.DYNAMIC)
                    .eq(MediaDraft::getStatus, current.getStatus()).eq(MediaDraft::getRevision, current.getRevision())
                    .set(MediaDraft::getTitle, draft.getTitle()).set(MediaDraft::getDescription, draft.getDescription())
                    .set(MediaDraft::getVisibility, draft.getVisibility()).set(MediaDraft::getAllowComment, draft.getAllowComment())
                    .set(MediaDraft::getExpiresAt, draft.getExpiresAt()).setIncrBy(MediaDraft::getRevision, 1);
            if (changedMedia) update.set(MediaDraft::getMediaJson, draft.getMediaJson()).set(MediaDraft::getDurationMs, draft.getDurationMs())
                    .set(MediaDraft::getCoverObjectKey, draft.getCoverObjectKey()).set(MediaDraft::getStatus, draft.getStatus())
                    .set(MediaDraft::getMediaVersion, draft.getMediaVersion()).set(MediaDraft::getPreparationId, draft.getPreparationId())
                    .set(MediaDraft::getPreparationAttempt, 0).set(MediaDraft::getPreparationRetry, false)
                    .set(MediaDraft::getPreparationAfter, draft.getPreparationAfter()).set(MediaDraft::getErrorMessage, null)
                    .set(MediaDraft::getPreparedJson, null).set(MediaDraft::getPreparedCoverObjectKey, null);
            int changed = observation.update(draftId, update);
            if (changed == 0) throw new BizException(ErrorCode.STATE_CONFLICT, "草稿已提交或状态已变化");
        }
        return view(draft);
    }

    @ObservedBusiness(BusinessOperation.SAVE_METADATA)
    public ContentDraftVO saveMetadata(Long draftId, Long userId, ContentMetadataDTO dto) {
        owned(draftId, userId);
        if (dto == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "草稿不能为空");
        int changed = drafts.update(null, editable(draftId, userId)
                .set(MediaDraft::getTitle, rules.title(dto.getTitle()))
                .set(MediaDraft::getDescription, rules.description(dto.getDescription()))
                .set(MediaDraft::getVisibility, "public")
                .set(MediaDraft::getAllowComment, rules.allowComment(dto.getAllowComment()))
                .set(MediaDraft::getExpiresAt, LocalDateTime.now().plusDays(7)).setIncrBy(MediaDraft::getRevision, 1));
        if (changed == 0) throw new BizException(ErrorCode.STATE_CONFLICT, "草稿已提交或状态已变化");
        return get(draftId, userId);
    }

    public ContentDraftVO get(Long draftId, Long userId) { return view(owned(draftId, userId)); }

    private MediaDraft owned(Long draftId, Long userId) {
        var draft = drafts.selectById(draftId);
        if (draft == null || !userId.equals(draft.getUserId()))
            throw new BizException(ErrorCode.NOT_FOUND, "草稿不存在");
        return draft;
    }

    private LambdaUpdateWrapper<MediaDraft> editable(Long draftId, Long userId) {
        return new LambdaUpdateWrapper<MediaDraft>().eq(MediaDraft::getId, draftId).eq(MediaDraft::getUserId, userId)
                .in(MediaDraft::getStatus, MediaDraftStatusEnum.UPLOADING, MediaDraftStatusEnum.PROCESSING, MediaDraftStatusEnum.READY, MediaDraftStatusEnum.FAILED);
    }

    private ContentDraftVO view(MediaDraft draft) {
        return new ContentDraftVO(draft.getId(), draft.getType().getCode(), draft.getStatus().getCode(), draft.getTitle(),
                draft.getDescription(), draft.getVisibility(), draft.getAllowComment(), draft.getCoverObjectKey(), draft.getObjectKey(), draft.getDurationMs(),
                draft.getType() == ContentTypeEnum.DYNAMIC ? JsonUtil.fromJson(draft.getMediaJson(), DynamicMedia.class) : null,
                draft.getErrorMessage(), draft.getMediaVersion(), draft.getPreparationId(), draft.getRevision());
    }
}

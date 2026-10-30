package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.DraftSubmissionState;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class MediaDraftService {

    private final MediaDraftMapper draftMapper;
    private final cn.diaojiahong.myyoutube.video.service.DraftObservation observation;

    /** 幂等创建: objectKey 唯一, 重复创建(事件重放/重试)返回已有草稿 */
    public Long createDraft(Long userId, String objectKey, String filename) {
        cn.diaojiahong.myyoutube.common.media.MediaInputs.objectKey(objectKey);
        if (userId == null
                || userId <= 0
                || objectKey.length() > 512
                || !objectKey.startsWith("uploads/video/" + userId + "/")
                || objectKey.split("/", -1).length != 5)
            throw new BizException(ErrorCode.FORBIDDEN, "视频素材不属于当前用户");
        MediaDraft existing = draftMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaDraft>()
                .eq(MediaDraft::getObjectKey, objectKey)
                .last("limit 1"));
        if (existing != null) {
            if (!userId.equals(existing.getUserId())) throw new BizException(ErrorCode.FORBIDDEN, "素材不属于当前用户");
            return existing.getId();
        }
        MediaDraft draft = new MediaDraft()
                .setId(IdWorker.getId())
                .setUserId(userId)
                .setType(ContentTypeEnum.VIDEO)
                .setVisibility("public")
                .setAllowComment(1)
                .setExpiresAt(java.time.LocalDateTime.now().plusDays(7))
                .setRevision(0)
                .setMediaVersion(0L)
                .setPreparationId(IdWorker.getId())
                .setPreparationAttempt(0)
                .setPreparationRetry(false)
                .setObjectKey(objectKey)
                .setOriginalFilename(filename)
                .setStatus(MediaDraftStatusEnum.UPLOADING);
        try {
            observation.insert(draft);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            MediaDraft winner = getByObjectKey(objectKey);
            if (winner == null) throw e;
            if (!userId.equals(winner.getUserId())) throw new BizException(ErrorCode.FORBIDDEN, "素材不属于当前用户");
            return winner.getId();
        }
        return draft.getId();
    }

    public MediaDraft getById(Long draftId) {
        return draftMapper.selectById(draftId);
    }

    public boolean submit(MediaDraft frozen, long durationMs) {
        LocalDateTime now = java.time.LocalDateTime.now();
        if (frozen.expiredAt(now) || frozen.getSubmissionState() != DraftSubmissionState.PENDING
                || !java.util.Objects.equals(frozen.getSubmissionRevision(), frozen.getRevision())
                || frozen.getSubmissionAfter() == null
                || frozen.getSubmissionDeadline() == null || !frozen.getSubmissionDeadline().isAfter(now)) {
            return false;
        }
        LambdaUpdateWrapper<MediaDraft> update = new LambdaUpdateWrapper<MediaDraft>()
                .eq(MediaDraft::getId, frozen.getId()).eq(MediaDraft::getUserId, frozen.getUserId())
                .eq(MediaDraft::getRevision, frozen.getRevision()).eq(MediaDraft::getStatus, MediaDraftStatusEnum.READY)
                .eq(MediaDraft::getMediaVersion, frozen.getMediaVersion()).eq(
                        MediaDraft::getPreparationId,
                        frozen.getPreparationId())
                .eq(MediaDraft::getPreparedJson, frozen.getPreparedJson())
                .gt(MediaDraft::getExpiresAt, now)
                .eq(MediaDraft::getSubmissionState, DraftSubmissionState.PENDING)
                .eq(MediaDraft::getSubmissionRevision, frozen.getRevision())
                // 租约到期允许接管；是否已经接管由租约值匹配决定。
                .eq(MediaDraft::getSubmissionAfter, frozen.getSubmissionAfter())
                .eq(MediaDraft::getSubmissionDeadline, frozen.getSubmissionDeadline()).gt(
                        MediaDraft::getSubmissionDeadline,
                        now)
                .set(MediaDraft::getStatus, MediaDraftStatusEnum.SUBMITTED)
                .set(MediaDraft::getSubmissionState, DraftSubmissionState.SUBMITTED).set(
                        MediaDraft::getSubmissionError,
                        null)
                .set(MediaDraft::getSubmissionAfter, null).set(MediaDraft::getSubmissionDeadline, null)
                .set(MediaDraft::getTitle, frozen.getTitle()).set(MediaDraft::getDescription, frozen.getDescription())
                .set(MediaDraft::getVisibility, "public").set(MediaDraft::getAllowComment, frozen.getAllowComment())
                .set(MediaDraft::getDurationMs, durationMs).setIncrBy(MediaDraft::getRevision, 1);
        return observation.update(frozen.getId(), update) > 0;
    }

    public MediaDraft getByObjectKey(String objectKey) {
        return draftMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaDraft>()
                .eq(MediaDraft::getObjectKey, objectKey)
                .last("limit 1"));
    }

    public MediaDraftPort.DraftInfo toInfo(MediaDraft draft) {
        return new MediaDraftPort.DraftInfo(draft.getId(), draft.getUserId(), draft.getObjectKey(),
                draft.getStatus().getCode(), draft.getDurationMs());
    }
}

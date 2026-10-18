package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaDraftService {

    private final MediaDraftMapper draftMapper;
    private final cn.diaojiahong.myyoutube.video.service.DraftObservation observation;

    /** 幂等创建: objectKey 唯一, 重复创建(事件重放/重试)返回已有草稿 */
    public Long createDraft(Long userId, String objectKey, String filename) {
        cn.diaojiahong.myyoutube.common.media.MediaInputs.objectKey(objectKey);
        if (userId == null || userId <= 0 || objectKey.length() > 512 || !objectKey.startsWith("uploads/video/" + userId + "/")
                || objectKey.split("/", -1).length != 5)
            throw new BizException(BizException.BIZ_ERROR, "视频素材不属于当前用户");
        MediaDraft existing = draftMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaDraft>()
                .eq(MediaDraft::getObjectKey, objectKey)
                .last("limit 1"));
        if (existing != null) {
            if (!userId.equals(existing.getUserId())) throw new BizException(BizException.BIZ_ERROR, "素材不属于当前用户");
            return existing.getId();
        }
        MediaDraft draft = new MediaDraft()
                .setId(IdWorker.getId())
                .setUserId(userId)
                .setType(ContentTypeEnum.VIDEO)
                .setVisibility("public")
                .setAllowComment(1)
                .setExpiresAt(java.time.LocalDateTime.now().plusDays(7)).setRevision(0).setMediaVersion(0L).setPreparationId(IdWorker.getId()).setPreparationAttempt(0).setPreparationRetry(false)
                .setObjectKey(objectKey)
                .setOriginalFilename(filename)
                .setStatus(MediaDraftStatusEnum.UPLOADING);
        try {
            observation.insert(draft);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            var winner = getByObjectKey(objectKey);
            if (winner == null) throw e;
            if (!userId.equals(winner.getUserId())) throw new BizException(BizException.BIZ_ERROR, "素材不属于当前用户");
            return winner.getId();
        }
        return draft.getId();
    }

    public MediaDraft getById(Long draftId) {
        return draftMapper.selectById(draftId);
    }

    public boolean submit(MediaDraft frozen, long durationMs) {
        return observation.update(frozen.getId(), new LambdaUpdateWrapper<MediaDraft>()
                .eq(MediaDraft::getId, frozen.getId()).eq(MediaDraft::getUserId, frozen.getUserId())
                .eq(MediaDraft::getRevision, frozen.getRevision()).eq(MediaDraft::getStatus, MediaDraftStatusEnum.READY)
                .eq(MediaDraft::getMediaVersion, frozen.getMediaVersion()).eq(MediaDraft::getPreparationId, frozen.getPreparationId())
                .eq(MediaDraft::getPreparedJson, frozen.getPreparedJson())
                .set(MediaDraft::getStatus, MediaDraftStatusEnum.SUBMITTED)
                .set(MediaDraft::getTitle, frozen.getTitle()).set(MediaDraft::getDescription, frozen.getDescription())
                .set(MediaDraft::getVisibility, "public").set(MediaDraft::getAllowComment, frozen.getAllowComment())
                .set(MediaDraft::getDurationMs, durationMs).setIncrBy(MediaDraft::getRevision, 1)) > 0;
    }

    public MediaDraft getByObjectKey(String objectKey) {
        return draftMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaDraft>()
                .eq(MediaDraft::getObjectKey, objectKey)
                .last("limit 1"));
    }

    /** 条件更新: 只允许状态机合法迁移, 未命中返回 false(调用方按重复消息短路处理) */
    public boolean transition(Long draftId, MediaDraftStatusEnum expect, MediaDraftStatusEnum target,
                              Long durationMs, String errorMessage) {
        String summary = errorMessage != null && errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
        return observation.update(draftId, new LambdaUpdateWrapper<MediaDraft>()
                .eq(MediaDraft::getId, draftId)
                .eq(MediaDraft::getStatus, expect).set(MediaDraft::getStatus, target)
                .set(durationMs != null, MediaDraft::getDurationMs, durationMs)
                .set(errorMessage != null, MediaDraft::getErrorMessage, summary)) > 0;
    }

    /** 过期清理: 从任一预期状态置 EXPIRED(终态, 不再变更) */
    public boolean expire(Long draftId, List<MediaDraftStatusEnum> expects) {
        return observation.update(draftId, new LambdaUpdateWrapper<MediaDraft>()
                .eq(MediaDraft::getId, draftId)
                .in(MediaDraft::getStatus, expects)
                .set(MediaDraft::getStatus, MediaDraftStatusEnum.EXPIRED)) > 0;
    }

    public MediaDraftPort.DraftInfo toInfo(MediaDraft draft) {
        return new MediaDraftPort.DraftInfo(draft.getId(), draft.getUserId(), draft.getObjectKey(),
                draft.getStatus().getCode(), draft.getDurationMs());
    }
}

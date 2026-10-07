package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
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

    /** 幂等创建: objectKey 唯一, 重复创建(事件重放/重试)返回已有草稿 */
    public Long createDraft(Long userId, String objectKey, String filename) {
        MediaDraft existing = draftMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaDraft>()
                .eq(MediaDraft::getObjectKey, objectKey)
                .last("limit 1"));
        if (existing != null) {
            return existing.getId();
        }
        MediaDraft draft = new MediaDraft()
                .setId(IdWorker.getId())
                .setUserId(userId)
                .setObjectKey(objectKey)
                .setOriginalFilename(filename)
                .setStatus(MediaDraftStatusEnum.UPLOADING);
        try {
            draftMapper.insert(draft);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return draftMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaDraft>()
                    .eq(MediaDraft::getObjectKey, objectKey).last("limit 1")).getId();
        }
        return draft.getId();
    }

    public MediaDraft getById(Long draftId) {
        return draftMapper.selectById(draftId);
    }

    public MediaDraft getByObjectKey(String objectKey) {
        return draftMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MediaDraft>()
                .eq(MediaDraft::getObjectKey, objectKey)
                .last("limit 1"));
    }

    /** 条件更新: 只允许状态机合法迁移, 未命中返回 false(调用方按重复消息短路处理) */
    public boolean transition(Long draftId, MediaDraftStatusEnum expect, MediaDraftStatusEnum target,
                              Long durationMs, String errorMessage) {
        MediaDraft patch = new MediaDraft()
                .setStatus(target)
                .setDurationMs(durationMs)
                .setErrorMessage(errorMessage != null && errorMessage.length() > 500
                        ? errorMessage.substring(0, 500) : errorMessage);
        return draftMapper.update(patch, new LambdaUpdateWrapper<MediaDraft>()
                .eq(MediaDraft::getId, draftId)
                .eq(MediaDraft::getStatus, expect)) > 0;
    }

    /** 过期清理: 从任一预期状态置 EXPIRED(终态, 不再变更) */
    public boolean expire(Long draftId, List<MediaDraftStatusEnum> expects) {
        return draftMapper.update(null, new LambdaUpdateWrapper<MediaDraft>()
                .eq(MediaDraft::getId, draftId)
                .in(MediaDraft::getStatus, expects)
                .set(MediaDraft::getStatus, MediaDraftStatusEnum.EXPIRED)) > 0;
    }

    public MediaDraftPort.DraftInfo toInfo(MediaDraft draft) {
        return new MediaDraftPort.DraftInfo(draft.getId(), draft.getUserId(), draft.getObjectKey(),
                draft.getStatus().getCode(), draft.getDurationMs());
    }
}

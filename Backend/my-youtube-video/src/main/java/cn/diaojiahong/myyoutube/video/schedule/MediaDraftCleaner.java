package cn.diaojiahong.myyoutube.video.schedule;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaUploadEventMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import cn.diaojiahong.myyoutube.video.service.impl.MediaDraftService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿草稿治理: 上传后事件丢失的自愈补桥 + 超期未发布草稿的存储回收
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaDraftCleaner {

    /** UPLOADING 滞留超此值时 HeadObject 补桥(对象在=事件丢了, 对象不在=直传放弃) */
    private static final int UPLOADING_STALE_HOURS = 6;
    /** 处理完不发布的保存期 */
    private static final int UNPUBLISHED_KEEP_DAYS = 7;

    private final MediaDraftMapper draftMapper;
    private final MediaDraftService mediaDraftService;
    private final S3StorageUtil storageUtil;
    private final RocketMQTemplate rocketMQTemplate;
    private final MediaTaskPort mediaTaskPort;

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 300_000)
    public void clean() {
        LocalDateTime now = LocalDateTime.now();
        List<MediaDraft> stale = draftMapper.selectList(new LambdaQueryWrapper<MediaDraft>()
                .in(MediaDraft::getStatus, MediaDraftStatusEnum.UPLOADING,
                        MediaDraftStatusEnum.READY, MediaDraftStatusEnum.FAILED)
                .lt(MediaDraft::getUpdateTime, now.minusHours(UPLOADING_STALE_HOURS))
                .last("limit 200"));
        for (MediaDraft draft : stale) {
            try {
                handle(draft, now);
            } catch (Exception e) {
                log.warn("草稿治理失败: draftId={}", draft.getId(), e);
            }
        }
    }

    private void handle(MediaDraft draft, LocalDateTime now) {
        if (MediaDraftStatusEnum.UPLOADING.equals(draft.getStatus())) {
            if (!draft.getUpdateTime().isBefore(now.minusHours(UPLOADING_STALE_HOURS))) {
                return;
            }
            if (objectExists(draft.getObjectKey())) {
                // 对象在但事件没到: 补发上传事件重新进管线
                log.warn("补桥上传事件: draftId={}", draft.getId());
                rocketMQTemplate.syncSend(MqConstants.TOPIC_MEDIA_UPLOAD_EVENT, MediaUploadEventMessage.builder()
                        .draftId(draft.getId())
                        .userId(draft.getUserId())
                        .objectKey(draft.getObjectKey())
                        .build());
            } else {
                // 直传从未完成或对象已被清: 过期回收
                mediaDraftService.expire(draft.getId(), List.of(MediaDraftStatusEnum.UPLOADING));
                log.info("直传未完成草稿过期: draftId={}", draft.getId());
            }
            return;
        }
        // READY/FAILED 超期: 原件+管线产物一并回收
        if (!draft.getUpdateTime().isBefore(now.minusDays(UNPUBLISHED_KEEP_DAYS))) {
            return;
        }
        if (mediaDraftService.expire(draft.getId(), List.of(MediaDraftStatusEnum.READY, MediaDraftStatusEnum.FAILED))) {
            deletePipelineArtifacts(draft.getId(), draft.getObjectKey());
            log.info("超期未发布草稿已回收: draftId={}", draft.getId());
        }
    }

    private boolean objectExists(String objectKey) {
        try {
            storageUtil.readHeadBytes(objectKey, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void deletePipelineArtifacts(Long draftId, String originalKey) {
        storageUtil.deleteObjectQuietly(originalKey);
        try {
            List<MediaTaskPort.TaskResult> results = mediaTaskPort.getTaskResults(draftId);
            if (results == null) {
                return;
            }
            for (MediaTaskPort.TaskResult t : results) {
                if (!"SUCCESS".equals(t.getStatus()) || t.getResultJson() == null) {
                    continue;
                }
                JsonObject json = JsonUtil.GSON.fromJson(t.getResultJson(), JsonObject.class);
                if (json.has("processedObjectKey")) {
                    storageUtil.deleteObjectQuietly(json.get("processedObjectKey").getAsString());
                }
                if (json.has("coverObjectKey") && !"video-cover/default-video.png".equals(
                        json.get("coverObjectKey").getAsString())) {
                    storageUtil.deleteObjectQuietly(json.get("coverObjectKey").getAsString());
                }
            }
        } catch (Exception e) {
            // transcode 不可用时只删原件, 产物留给下一轮/人工
            log.warn("管线产物清理失败(原件已删): draftId={}", draftId, e);
        }
    }
}

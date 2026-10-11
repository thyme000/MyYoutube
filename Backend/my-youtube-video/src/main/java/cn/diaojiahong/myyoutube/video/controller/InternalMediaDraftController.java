package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.NotificationPublisher;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.RefTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.service.impl.MediaDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * video 服务内部接口: 上传草稿, 供 user 服务 presign 链路与 transcode 编排器经 Feign 调用
 */
@RestController
@RequestMapping("/internal/video/draft")
@RequiredArgsConstructor
public class InternalMediaDraftController {

    private final MediaDraftService mediaDraftService;
    private final NotificationPublisher notificationPublisher;

    @PostMapping("/create")
    public MediaDraftPort.DraftCreated createDraft(@RequestBody MediaDraftPort.DraftCreate create) {
        Long draftId = mediaDraftService.createDraft(create.getUserId(), create.getObjectKey(), create.getFilename());
        return new MediaDraftPort.DraftCreated(draftId);
    }

    @GetMapping("/{draftId}")
    public MediaDraftPort.DraftInfo getDraft(@PathVariable Long draftId) {
        MediaDraft draft = mediaDraftService.getById(draftId);
        return draft == null ? null : mediaDraftService.toInfo(draft);
    }

    @GetMapping("/by-object-key")
    public MediaDraftPort.DraftInfo getByObjectKey(@RequestParam("key") String objectKey) {
        MediaDraft draft = mediaDraftService.getByObjectKey(objectKey);
        return draft == null ? null : mediaDraftService.toInfo(draft);
    }

    @PostMapping("/{draftId}/status/{statusCode}")
    public boolean updateStatus(@PathVariable Long draftId,
                                @PathVariable String statusCode,
                                @RequestBody MediaDraftPort.StatusUpdate update) {
        MediaDraft draft = mediaDraftService.getById(draftId);
        if (draft == null) {
            return false;
        }
        MediaDraftStatusEnum target = MediaDraftStatusEnum.valueOf(statusCode.toUpperCase());
        // 提交只允许业务事务冻结输入，处理回调不能把草稿置为 SUBMITTED。
        MediaDraftStatusEnum expect = switch (target) {
            case PROCESSING -> MediaDraftStatusEnum.UPLOADING;
            case READY, FAILED -> MediaDraftStatusEnum.PROCESSING;
            default -> null;
        };
        if (expect == null) {
            return false;
        }
        boolean hit = mediaDraftService.transition(draftId, expect, target,
                update.getDurationMs(), update.getErrorMessage());
        if (hit && draft.getUserId() != null) {
            publishDraftNotice(draft.getUserId(), draftId, target, update.getErrorMessage());
        }
        return hit;
    }

    /** 状态迁移成功即补门铃, 前端 SSE 弹提醒 */
    private void publishDraftNotice(Long userId, Long draftId, MediaDraftStatusEnum target, String errorMessage) {
        try {
            UserNotification n = new UserNotification();
            n.setReceiverId(userId);
            n.setType(NotificationTypeEnum.SYSTEM_NOTICE.getCode());
            n.setRefType(RefTypeEnum.SYSTEM.getCode());
            n.setRefId(draftId);
            if (target == MediaDraftStatusEnum.READY) {
                n.setTitle("视频处理完成");
                n.setContent("你的视频已完成处理, 可以发布了");
            } else if (target == MediaDraftStatusEnum.FAILED) {
                n.setTitle("视频处理失败");
                n.setContent(errorMessage != null ? errorMessage : "处理过程中出现问题, 请重新上传");
            } else {
                return;
            }
            notificationPublisher.publish(n);
        } catch (Exception e) {
            // 通知失败不影响状态迁移
        }
    }
}

package cn.diaojiahong.myyoutube.content.controller;

import cn.diaojiahong.myyoutube.content.api.MediaDraftPort;
import cn.diaojiahong.myyoutube.content.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.content.service.impl.MediaDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容服务内部接口: 上传草稿, 供 user 服务 presign 链路与 transcode 编排器经 Feign 调用
 */
@RestController
@RequestMapping("/internal/media-drafts")
@RequiredArgsConstructor
public class InternalMediaDraftController {

    private final MediaDraftService mediaDraftService;
    private final cn.diaojiahong.myyoutube.content.service.DraftPreparationService preparation;

    @PostMapping("/{draftId}/uploaded")
    public boolean uploaded(@PathVariable Long draftId) {
        return preparation.uploaded(draftId);
    }

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

}

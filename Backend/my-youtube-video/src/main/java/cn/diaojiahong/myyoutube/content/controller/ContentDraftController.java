package cn.diaojiahong.myyoutube.content.controller;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.service.ContentDraftService;
import cn.diaojiahong.myyoutube.content.service.DraftPublicationService;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.video.domain.dto.ContentMetadataDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicDraftDTO;
import cn.diaojiahong.myyoutube.video.domain.vo.ContentDraftVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/contents/drafts")
@RequiredArgsConstructor
public class ContentDraftController {
    private final ContentDraftService drafts;
    private final DraftPublicationService publications;
    private final cn.diaojiahong.myyoutube.video.service.DraftPreparationService preparation;
    private final cn.diaojiahong.myyoutube.common.utils.S3StorageUtil storage;

    @PostMapping("/{draftId}/preparation/retry")
    public Result<Boolean> retryPreparation(@PathVariable Long draftId) {
        return Result.success(preparation.retry(draftId, userId()));
    }

    @PostMapping("/{draftId}/submit")
    public Result<DraftPublicationService.Receipt> submit(@PathVariable Long draftId, @RequestBody DraftPublicationService.Request request) {
        return Result.success(publications.request(draftId, userId(), request.revision()));
    }

    @GetMapping("/pending")
    public Result<PageResult<ContentDraftVO>> pending(@ModelAttribute PageQuery query) {
        return Result.success(publications.pending(userId(), query));
    }

    @PostMapping("/{draftId}/submission/retry")
    public Result<DraftPublicationService.Receipt> retrySubmission(@PathVariable Long draftId, @RequestBody DraftPublicationService.Request request) {
        return Result.success(publications.retry(draftId, userId(), request.revision()));
    }

    @PostMapping("/{draftId}/submission/cancel")
    public Result<ContentDraftVO> cancelSubmission(@PathVariable Long draftId, @RequestBody DraftPublicationService.Request request) {
        return Result.success(publications.cancel(draftId, userId(), request.revision()));
    }

    @PostMapping("/dynamic")
    public Result<ContentDraftVO> createDynamic(@RequestBody DynamicDraftDTO dto) {
        return Result.success(drafts.saveDynamic(null, userId(), dto));
    }

    @PutMapping("/{draftId}/dynamic")
    public Result<ContentDraftVO> updateDynamic(@PathVariable Long draftId, @RequestBody DynamicDraftDTO dto) {
        return Result.success(drafts.saveDynamic(draftId, userId(), dto));
    }

    @PutMapping("/{draftId}/metadata")
    public Result<ContentDraftVO> updateMetadata(@PathVariable Long draftId, @RequestBody ContentMetadataDTO dto) {
        return Result.success(drafts.saveMetadata(draftId, userId(), dto));
    }

    @GetMapping("/{draftId}")
    public Result<ContentDraftVO> get(@PathVariable Long draftId) {
        return Result.success(drafts.get(draftId, userId()));
    }

    @GetMapping("/{draftId}/previews")
    public Result<java.util.Map<String, String>> previews(@PathVariable Long draftId) {
        var draft = drafts.get(draftId, userId());
        var urls = new java.util.LinkedHashMap<String, String>();
        if (draft.dynamic() != null) for (var image : draft.dynamic().items())
            urls.put(image.imageObjectKey(), storage.signGetUrl(image.imageObjectKey(), 1200));
        return Result.success(urls);
    }

    private Long userId() {
        String id = UserContext.getUserId();
        if (id == null) throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        return Long.valueOf(id);
    }
}

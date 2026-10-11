package cn.diaojiahong.myyoutube.content.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.service.ContentDraftService;
import cn.diaojiahong.myyoutube.content.service.ContentSubmissionService;
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
    private final ContentSubmissionService submissions;
    private final cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort tasks;

    @PostMapping("/{draftId}/submit")
    public Result<ContentSubmissionService.Receipt> submit(@PathVariable Long draftId) {
        return Result.success(submissions.submit(draftId, userId()));
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

    @GetMapping("/{draftId}/tasks")
    public Result<java.util.List<cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort.TaskResult>> tasks(@PathVariable Long draftId) {
        var draft = drafts.get(draftId, userId());
        return Result.success("video".equals(draft.type()) ? tasks.getTaskResults(draftId) : java.util.List.of());
    }
    @GetMapping("/{draftId}")
    public Result<ContentDraftVO> get(@PathVariable Long draftId) {
        return Result.success(drafts.get(draftId, userId()));
    }

    private Long userId() {
        String id = UserContext.getUserId();
        if (id == null) throw new BizException(BizException.USER_ERROR, "用户未登录");
        return Long.valueOf(id);
    }
}

package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.video.api.ContentAdminPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * video 服务内部接口: 管理端视频操作
 */
@RestController
@RequestMapping("/internal/admin/content")
@RequiredArgsConstructor
public class InternalAdminContentController {

    private final cn.diaojiahong.myyoutube.video.service.ContentUnderstandingService understanding;
    private final cn.diaojiahong.myyoutube.video.service.ReviewDecisionService decisions;

    private final IContentService contentService;
    private final cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry strategies;

    private final cn.diaojiahong.myyoutube.content.service.ContentReadService reads;

    @PostMapping("/media")
    public Result<cn.diaojiahong.myyoutube.video.api.dto.ContentMediaVO> media(@RequestBody ContentAdminPort.ContentId id) {
        return Result.success(reads.adminMedia(id.getContentId()));
    }

    @PostMapping("/mark-status")
    public Result<Boolean> markStatus(@RequestBody ContentAdminPort.MarkStatus mark) {
        if ("published".equals(mark.getStatusCode()))
            return Result.success(decisions.manual(mark.getContentId(), true, mark.getReason(), mark.getActorId()));
        if ("banned".equals(mark.getStatusCode())) {
            var video = contentService.getById(mark.getContentId());
            boolean published = video != null && "published".equals(video.getStatus());
            return Result.success(published ? decisions.banPublished(mark.getContentId(), mark.getReason(), mark.getActorId())
                    : decisions.manual(mark.getContentId(), false, mark.getReason(), mark.getActorId()));
        }
        throw new BizException(ErrorCode.INVALID_ARGUMENT, "不支持的审核状态");
    }

    @PostMapping("/content/get")
    public Result<Map<String, Object>> contentStatus(@RequestBody ContentAdminPort.ContentId id) {
        return Result.success(understanding.status(id.getContentId()));
    }
    @PostMapping("/content/waiting")
    public Result<List<Map<String, Object>>> waitingContent(@RequestBody ContentAdminPort.AdminContentQuery query) {
        return Result.success(understanding.waiting((int) query.getPageNo(), (int) query.getPageSize()).stream().map(row -> {
            var video = contentService.getById(row.contentId());
            return Map.<String, Object>of("contentId", row.contentId().toString(), "title", video == null ? "" : video.getTitle(),
                    "state", row.status(), "pipelineId", row.pipelineId().toString());
        }).toList());
    }

    @PostMapping("/page")
    public Result<PageResult<ContentSummary>> pageContents(@RequestBody ContentAdminPort.AdminContentQuery query) {
        if (query.getStatusCode() != null) {
            try { cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum.fromCode(query.getStatusCode()); }
            catch (IllegalArgumentException error) { throw new BizException(ErrorCode.INVALID_ARGUMENT, "审核状态无效", error); }
        }
        var type = query.getType() == null ? null : cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum.fromCode(query.getType());
        if (query.getType() != null && type == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "内容类型无效");
        var page = contentService.lambdaQuery().eq(Content::getIsDelete, 0)
                .eq(query.getStatusCode() != null, Content::getStatus, query.getStatusCode())
                .eq(type != null, Content::getType, type)
                .apply("review".equals(query.getStatusCode()), "EXISTS (SELECT 1 FROM content_understanding u WHERE u.content_id = content.id AND u.state = 'MANUAL_PENDING')")
                .eq(query.getUserId() != null, Content::getAuthorId, query.getUserId())
                .eq(query.getCategoryCode() != null, Content::getCategoryCode, query.getCategoryCode())
                .orderByDesc(Content::getCreateTime)
                .page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Content>(query.getPageNo(), query.getPageSize()));
        var summaries = page.getRecords().stream().map(c -> {
            var media = strategies.get(c.getType()).loadMedia(List.of(c.getId())).get(c.getId());
            return cn.diaojiahong.myyoutube.content.support.ContentCards.summary(c, media);
        }).toList();
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), summaries));
    }
}

package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.video.api.enums.CommentReportStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.CommentReport;
import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.service.ICommentReportService;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.api.CommentAdminPort;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * video 服务内部接口: 管理端评论/举报操作
 */
@RestController
@RequestMapping("/internal/admin/comment")
@RequiredArgsConstructor
public class InternalAdminCommentController {

    private final IVideoCommentService videoCommentService;
    private final ICommentReportService commentReportService;

    @PostMapping("/block")
    public Result<Void> block(@RequestBody CommentAdminPort.CommentId id) {
        VideoComment comment = videoCommentService.getById(id.getCommentId());
        if (comment == null || comment.getStatus().equals(CommentStatusEnum.DELETED.getCode())
                || comment.getStatus().equals(CommentStatusEnum.BLOCKED.getCode())) {
            return Result.success(null);
        }
        comment.setStatus(CommentStatusEnum.BLOCKED.getCode());
        videoCommentService.updateById(comment);
        commentReportService.lambdaUpdate()
                .eq(CommentReport::getCommentId, id.getCommentId())
                .set(CommentReport::getStatus, CommentReportStatusEnum.PROCESSED)
                .update();
        return Result.success(null);
    }

    @PostMapping("/restore")
    public Result<Void> restore(@RequestBody CommentAdminPort.CommentId id) {
        VideoComment comment = videoCommentService.getById(id.getCommentId());
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        if (comment.getStatus().equals(CommentStatusEnum.DELETED.getCode())) {
            return Result.fail("评论已被删除");
        }
        if (comment.getStatus().equals(CommentStatusEnum.VISIBLE.getCode())) {
            return Result.success(null);
        }
        comment.setStatus(CommentStatusEnum.VISIBLE.getCode());
        videoCommentService.updateById(comment);
        return Result.success(null);
    }

    @PostMapping("/page/blocked")
    public Result<PageResult<CommentAdminPort.CommentSummary>> pageBlockedComments(
            @RequestBody CommentAdminPort.PageQuery q) {
        Page<VideoComment> page = videoCommentService.lambdaQuery()
                .eq(VideoComment::getStatus, CommentStatusEnum.BLOCKED.getCode())
                .orderByDesc(VideoComment::getCreateTime)
                .page(new Page<>(q.getPageNo(), q.getPageSize()));
        List<CommentAdminPort.CommentSummary> list = page.getRecords().stream()
                .map(c -> new CommentAdminPort.CommentSummary(c.getId(), c.getContent(), c.getUserId(), c.getCreateTime()))
                .toList();
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), list));
    }

    @PostMapping("/report/ignore")
    public Result<Void> ignoreReport(@RequestBody CommentAdminPort.CommentId id) {
        commentReportService.lambdaUpdate()
                .eq(CommentReport::getCommentId, id.getCommentId())
                .set(CommentReport::getStatus, CommentReportStatusEnum.PROCESSED)
                .update();
        return Result.success(null);
    }

    @PostMapping("/report/page")
    public Result<PageResult<CommentAdminPort.ReportSummary>> pageReports(
            @RequestBody CommentAdminPort.ReportQuery q) {
        Page<CommentReport> page = commentReportService.lambdaQuery()
                .eq(CommentReport::getStatus, CommentReportStatusEnum.PENDING.getCode())
                .eq(q.getReasonCode() != null, CommentReport::getReasonCode, q.getReasonCode())
                .eq(q.getReporterId() != null, CommentReport::getReporterUserId, q.getReporterId())
                .orderByDesc(CommentReport::getCreatedAt)
                .page(new Page<>(q.getPageNo(), q.getPageSize()));
        List<CommentReport> records = page.getRecords();
        if (records.isEmpty()) {
            return Result.success(PageResult.of(0, 0, List.of()));
        }
        List<Long> commentIds = records.stream().map(CommentReport::getCommentId).toList();
        Map<Long, VideoComment> commentMap = videoCommentService.lambdaQuery()
                .eq(VideoComment::getStatus, CommentStatusEnum.VISIBLE.getCode())
                .in(VideoComment::getId, commentIds)
                .list().stream()
                .collect(Collectors.toMap(VideoComment::getId, Function.identity()));
        List<CommentAdminPort.ReportSummary> list = records.stream()
                .filter(r -> commentMap.containsKey(r.getCommentId()))
                .map(r -> new CommentAdminPort.ReportSummary(r.getCommentId(), r.getRemark(),
                        r.getReporterUserId(), r.getCreatedAt(),
                        commentMap.get(r.getCommentId()).getContent()))
                .toList();
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), list));
    }
}

package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.video.api.enums.CommentReportStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import cn.diaojiahong.myyoutube.comment.domain.po.CommentReport;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.service.ICommentReportService;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.video.api.CommentAdminPort;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
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
public class InternalAdminCommentController implements CommentAdminPort {

    private final IContentCommentService videoCommentService;
    private final ICommentReportService commentReportService;

    @PostMapping("/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@RequestBody CommentAdminPort.CommentId id) {
        ContentComment comment = videoCommentService.getById(id.getCommentId());
        if (comment == null || comment.getStatus().equals(CommentStatusEnum.DELETED.getCode())
                || comment.getStatus().equals(CommentStatusEnum.BLOCKED.getCode())) {
            return;
        }
        comment.setStatus(CommentStatusEnum.BLOCKED.getCode());
        videoCommentService.updateById(comment);
        commentReportService.lambdaUpdate()
                .eq(CommentReport::getCommentId, id.getCommentId())
                .set(CommentReport::getStatus, CommentReportStatusEnum.PROCESSED)
                .update();
    }

    @PostMapping("/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@RequestBody CommentAdminPort.CommentId id) {
        ContentComment comment = videoCommentService.getById(id.getCommentId());
        if (comment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评论不存在");
        }

        if (comment.getStatus().equals(CommentStatusEnum.DELETED.getCode())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "评论已被删除");
        }

        if (comment.getStatus().equals(CommentStatusEnum.VISIBLE.getCode())) {
            return;
        }

        comment.setStatus(CommentStatusEnum.VISIBLE.getCode());
        videoCommentService.updateById(comment);
    }

    @PostMapping("/page/blocked")
    public PageResult<CommentAdminPort.CommentSummary> pageBlockedComments(
            @RequestBody CommentAdminPort.PageQuery q) {
        Page<ContentComment> page = videoCommentService.lambdaQuery()
                .eq(ContentComment::getStatus, CommentStatusEnum.BLOCKED.getCode())
                .orderByDesc(ContentComment::getCreateTime)
                .page(new Page<>(q.getPageNo(), q.getPageSize()));
        List<CommentAdminPort.CommentSummary> list = page.getRecords().stream()
                .map(c -> new CommentAdminPort.CommentSummary(
                        c.getId(),
                        c.getContent(),
                        c.getUserId(),
                        c.getCreateTime()))
                .toList();
        return PageResult.of(page.getTotal(), page.getPages(), list);
    }

    @PostMapping("/report/ignore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ignoreReport(@RequestBody CommentAdminPort.CommentId id) {
        commentReportService.lambdaUpdate()
                .eq(CommentReport::getCommentId, id.getCommentId())
                .set(CommentReport::getStatus, CommentReportStatusEnum.PROCESSED)
                .update();
    }

    @PostMapping("/report/page")
    public PageResult<CommentAdminPort.ReportSummary> pageReports(
            @RequestBody CommentAdminPort.ReportQuery q) {
        Page<CommentReport> page = commentReportService.lambdaQuery()
                .eq(CommentReport::getStatus, CommentReportStatusEnum.PENDING.getCode())
                .eq(q.getReasonCode() != null, CommentReport::getReasonCode, q.getReasonCode())
                .eq(q.getReporterId() != null, CommentReport::getReporterUserId, q.getReporterId())
                .orderByDesc(CommentReport::getCreatedAt)
                .page(new Page<>(q.getPageNo(), q.getPageSize()));
        List<CommentReport> records = page.getRecords();
        if (records.isEmpty()) {
            return PageResult.of(page.getTotal(), page.getPages(), List.of());
        }

        List<Long> commentIds = records.stream().map(CommentReport::getCommentId).toList();
        Map<Long, ContentComment> commentMap = videoCommentService.lambdaQuery()
                .eq(ContentComment::getStatus, CommentStatusEnum.VISIBLE.getCode())
                .in(ContentComment::getId, commentIds)
                .list().stream()
                .collect(Collectors.toMap(ContentComment::getId, Function.identity()));
        List<CommentAdminPort.ReportSummary> list = records.stream()
                .filter(r -> commentMap.containsKey(r.getCommentId()))
                .map(r -> new CommentAdminPort.ReportSummary(r.getCommentId(), r.getRemark(),
                        r.getReporterUserId(), r.getCreatedAt(),
                        commentMap.get(r.getCommentId()).getContent()))
                .toList();
        return PageResult.of(page.getTotal(), page.getPages(), list);
    }
}

package cn.diaojiahong.myyoutube.comment.controller;


import cn.diaojiahong.myyoutube.comment.domain.dto.CommentReportDTO;
import cn.diaojiahong.myyoutube.comment.domain.po.CommentReport;
import cn.diaojiahong.myyoutube.comment.service.ICommentReportService;
import cn.diaojiahong.myyoutube.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 评论举报信息表 前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-04-14
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "评论模块")
@RequestMapping("/comment/report")
public class CommentReportController {

    private final ICommentReportService commentReportService;

    /**
     * 用户举报评论
     * @param commentReportDTO
     * @return
     */
    @Operation(summary = "用户举报评论")
    @PutMapping()
    public Result<Void> reportComment(@Valid @RequestBody CommentReportDTO commentReportDTO) {
        return commentReportService.reportComment(commentReportDTO);
    }
}

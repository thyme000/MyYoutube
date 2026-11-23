package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminCommentReportPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminContentPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentReportVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminContentVO;
import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.content.api.ContentAdminPort;
import cn.diaojiahong.myyoutube.content.api.ContentAdminPort.AdminContentQuery;
import cn.diaojiahong.myyoutube.content.api.ContentAdminPort.ContentId;
import cn.diaojiahong.myyoutube.content.api.ContentAdminPort.ReviewMedia;
import cn.diaojiahong.myyoutube.content.api.dto.ContentMediaVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@Tag(name = "管理模块")
@RequiredArgsConstructor
public class AdminController {

    private final IAdminService adminService;
    private final ContentAdminPort contentAdminPort;
    private final AdminAuthorization authorization;

    public record Decision(@NotBlank String reason) {
    }

    @GetMapping("/content/{contentId}/media")
    public Result<ContentMediaVO> media(@PathVariable Long contentId) {
        Long viewer = authorization.requireAdmin();
        return Result.success(contentAdminPort.media(
                new ReviewMedia(contentId, viewer)));
    }

    @GetMapping("/content/{contentId}")
    public Result<Map<String, Object>> content(@PathVariable Long contentId) {
        authorization.requireAdmin();

        return Result.success(contentAdminPort.contentStatus(new ContentId(contentId)));
    }

    @GetMapping("/content/waiting")
    public Result<List<Map<String, Object>>> waiting(@RequestParam(defaultValue = "1") long pageNo,
                                                                       @RequestParam(defaultValue = "20") long pageSize) {
        authorization.requireAdmin();

        return Result.success(contentAdminPort.waitingContent(new AdminContentQuery(
                null,
                null,
                null,
                pageNo,
                pageSize,
                null)));
    }

    @Operation(summary = "审核通过")
    @PostMapping("/review/pass/{contentId}")
    public Result<String> pass(@PathVariable Long contentId, @Valid @RequestBody Decision decision) {
        return Result.success(adminService.pass(contentId, decision.reason()));
    }

    @Operation(summary = "审核不通过")
    @PostMapping("/review/ban/{contentId}")
    public Result<String> ban(@PathVariable Long contentId, @Valid @RequestBody Decision decision) {
        return Result.success(adminService.ban(contentId, decision.reason()));
    }

    @Operation(summary = "查看所有带审查的视频")
    @GetMapping("/list")
    public Result<PageResult<AdminContentVO>> getContentsPage(@ModelAttribute AdminContentPageQueryDTO adminVideoPageQueryDTO) {
        return Result.success(adminService.getContentsPage(adminVideoPageQueryDTO));
    }

    @Operation(summary = "设置评论违规")
    @PostMapping("/comment/blocked/{commentId}")
    public Result<Void> blockComment(@PathVariable Long commentId) {
        adminService.blockComment(commentId);
        return Result.success();
    }

    @Operation(summary = "查看所有违规评论")
    @GetMapping("/comment/list")
    public Result<PageResult<AdminCommentVO>> getCommentsPage(@ModelAttribute PageQuery pageQuery) {
        return Result.success(adminService.getCommentsPage(pageQuery));
    }

    @Operation(summary = "恢复违规评论")
    @PostMapping("/comment/restore/{commentId}")
    public Result<Void> restoreComment(@PathVariable Long commentId) {
        adminService.restoreComment(commentId);
        return Result.success();
    }

    @Operation(summary = "忽略举报评论")
    @PostMapping("/comment/report/ignore/{commentId}")
    public Result<Void> ignoreReportComment(@PathVariable Long commentId) {
        adminService.ignoreReportComment(commentId);
        return Result.success();
    }

    @Operation(summary = "查看用户举报评论")
    @GetMapping("/comment/report")
    public Result<PageResult<AdminCommentReportVO>> getReportCommentsPage(@ModelAttribute AdminCommentReportPageQueryDTO pageQuery) {
        return Result.success(adminService.getReportCommentsPage(pageQuery));
    }
}

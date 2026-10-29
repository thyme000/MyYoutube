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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
@Tag(name = "管理模块")
@RequiredArgsConstructor
public class AdminController {

    private final IAdminService adminService;
    private final cn.diaojiahong.myyoutube.video.api.ContentAdminPort contentAdminPort;
    private final AdminAuthorization authorization;
    public record Decision(@jakarta.validation.constraints.NotBlank String reason) {}

    @GetMapping("/content/{contentId}/media")
    public Result<cn.diaojiahong.myyoutube.video.api.dto.ContentMediaVO> media(@PathVariable Long contentId) {
        authorization.requireAdmin();

        return Result.success(contentAdminPort.media(new cn.diaojiahong.myyoutube.video.api.ContentAdminPort.ContentId(contentId)));
    }

    @GetMapping("/content/{contentId}")
    public Result<java.util.Map<String, Object>> content(@PathVariable Long contentId) {
        authorization.requireAdmin();

        return Result.success(contentAdminPort.contentStatus(new cn.diaojiahong.myyoutube.video.api.ContentAdminPort.ContentId(contentId)));
    }
    @GetMapping("/content/waiting")
    public Result<java.util.List<java.util.Map<String, Object>>> waiting(@RequestParam(defaultValue = "1") long pageNo,
                                                                       @RequestParam(defaultValue = "20") long pageSize) {
        authorization.requireAdmin();

        return Result.success(contentAdminPort.waitingContent(new cn.diaojiahong.myyoutube.video.api.ContentAdminPort.AdminContentQuery(null, null, null, pageNo, pageSize, null)));
    }


    /**
     * 审核通过
     * @param contentId 视频id
     * @return
     */
    @Operation(summary = "审核通过")
    @PostMapping("/review/pass/{contentId}")
    public Result<String> pass(@PathVariable Long contentId, @jakarta.validation.Valid @RequestBody Decision decision) {
        return Result.success(adminService.pass(contentId, decision.reason()));
    }

    /**
     * 禁用视频
     * @param contentId 视频id
     * @return
     */
    @Operation(summary = "审核不通过")
    @PostMapping("/review/ban/{contentId}")
    public Result<String> ban(@PathVariable Long contentId, @jakarta.validation.Valid @RequestBody Decision decision) {
        return Result.success(adminService.ban(contentId, decision.reason()));
    }

    /**
     * 分页查询所有的视频
     * @param adminVideoPageQueryDTO
     * @return
     */
    @Operation(summary = "查看所有带审查的视频")
    @GetMapping("/list")
    public Result<PageResult<AdminContentVO>> getContentsPage(@ModelAttribute AdminContentPageQueryDTO adminVideoPageQueryDTO) {
        return Result.success(adminService.getContentsPage(adminVideoPageQueryDTO));
    }

    /**
     * 设置评论违规
     * @param commentId
     * @return
     */
    @Operation(summary = "设置评论违规")
    @PostMapping("/comment/blocked/{commentId}")
    public Result<Void> blockComment(@PathVariable Long commentId) {
        adminService.blockComment(commentId);
        return Result.success();
    }

    /**
     * 分页查询所有的违规评论
     * @param pageQuery
     * @return
     */
    @Operation(summary = "查看所有违规评论")
    @GetMapping("/comment/list")
    public Result<PageResult<AdminCommentVO>> getCommentsPage(@ModelAttribute PageQuery pageQuery) {
        return Result.success(adminService.getCommentsPage(pageQuery));
    }

    /**
     * 恢复违规评论
     * @param commentId
     * @return
     */
    @Operation(summary = "恢复违规评论")
    @PostMapping("/comment/restore/{commentId}")
    public Result<Void> restoreComment(@PathVariable Long commentId) {
        adminService.restoreComment(commentId);
        return Result.success();
    }

    /**
     * 忽略举报评论
     * @param commentId
     * @return
     */
    @Operation(summary = "忽略举报评论")
    @PostMapping("/comment/report/ignore/{commentId}")
    public Result<Void> ignoreReportComment(@PathVariable Long commentId) {
        adminService.ignoreReportComment(commentId);
        return Result.success();
    }

    /**
     * 分页查询所有的举报评论
     * @param pageQuery
     * @return
     */
    @Operation(summary = "查看用户举报评论")
    @GetMapping("/comment/report")
    public Result<PageResult<AdminCommentReportVO>> getReportCommentsPage(@ModelAttribute AdminCommentReportPageQueryDTO pageQuery) {
        return Result.success(adminService.getReportCommentsPage(pageQuery));
    }
}

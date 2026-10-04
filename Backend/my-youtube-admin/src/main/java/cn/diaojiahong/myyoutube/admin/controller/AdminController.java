package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.admin.domain.dto.AdminCommentReportPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.dto.AdminVideoPageQueryDTO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentReportVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminCommentVO;
import cn.diaojiahong.myyoutube.admin.domain.vo.AdminVideoVO;
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

    /**
     * 审核通过
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "审核通过")
    @PostMapping("/review/pass/{videoId}")
    public Result<String> pass(@PathVariable Long videoId) {
        return Result.success(adminService.pass(videoId));
    }

    /**
     * 禁用视频
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "审核不通过")
    @PostMapping("/review/ban/{videoId}")
    public Result<String> ban(@PathVariable Long videoId) {
        return Result.success(adminService.ban(videoId));
    }

    /**
     * 分页查询所有的视频
     * @param adminVideoPageQueryDTO
     * @return
     */
    @Operation(summary = "查看所有带审查的视频")
    @GetMapping("/list")
    public Result<PageResult<AdminVideoVO>> getVideosPage(@ModelAttribute AdminVideoPageQueryDTO adminVideoPageQueryDTO) {
        return Result.success(adminService.getVideosPage(adminVideoPageQueryDTO));
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

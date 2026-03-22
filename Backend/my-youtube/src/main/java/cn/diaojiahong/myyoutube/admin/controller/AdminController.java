package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        return adminService.pass(videoId);
    }

    /**
     * 禁用视频
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "审核不通过")
    @PostMapping("/review/ban/{videoId}")
    public Result<String> ban(@PathVariable Long videoId) {
        return adminService.ban(videoId);
    }
}

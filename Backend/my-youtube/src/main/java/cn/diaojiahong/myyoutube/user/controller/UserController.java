package cn.diaojiahong.myyoutube.user.controller;


import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.vo.UserVO;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

/**
 * <p>
 * 用户核心表（视频发布者/网站登录用户） 前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-03-18
 */
@RestController
@Slf4j
@RequestMapping("/user")
@RequiredArgsConstructor
@Tag(name = "用户模块")
public class UserController {

    private final IUserService userService;

    /**
     * 获取用户信息
     */
    @Operation(summary = "获取用户信息")
    @GetMapping("/info")
    public Result<UserVO> getUserInfo() {
        return userService.getUserInfo();
    }

    /**
     * 更新用户头像
     *
     * @param avatar 头像文件
     * @return
     */
    @Operation(summary = "用户修改头像")
    @PostMapping("/avatar")
    public Result<String> updateAvatar(@RequestParam("avatar") MultipartFile avatar) {
        return userService.updateAvatar(avatar);
    }

    /**
     * 更新用户背景图片
     *
     * @param bgImage 背景图片文件
     * @return
     */
    @Operation(summary = "用户修改背景图片")
    @PostMapping("/bg-image")
    public Result<String> updateBgImage(@RequestParam("bgImage") MultipartFile bgImage) {
        return userService.updateBgImage(bgImage);
    }

    /**
     * 更新用户资料
     * @param id
     * @param userDTO
     * @return
     */
    @Operation(summary = "用户修改资料")
    @PutMapping("/{id}")
    public Result<String> updateUser(@PathVariable Long id, @RequestBody UserDTO userDTO) {
        return userService.updateUser(id, userDTO);
    }
}
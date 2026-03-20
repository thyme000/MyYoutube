package cn.diaojiahong.myyoutube.user.controller;


import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

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
public class UserController {

    private final IUserService userService;

    /**
     * 获取用户信息
     */
    @GetMapping("/info")
    public Result<User> getUserInfo() {
        return Result.success();
    }

}

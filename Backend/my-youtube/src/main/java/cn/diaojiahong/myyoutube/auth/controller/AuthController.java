package cn.diaojiahong.myyoutube.auth.controller;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.auth.domain.dto.CaptchaDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.ForgetPasswordDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.LoginRequestDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.auth.service.IAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping()
@RequiredArgsConstructor
@Tag(name = "登录模块")
public class AuthController {

    private final IAuthService authService;

    /**
     * 用户登录
     *
     * @param loginRequestDTO 用户提交的登录表单
     * @return
     */
    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginRequestDTO loginRequestDTO) {
        return authService.login(loginRequestDTO);
    }

    /**
     * 用户注册
     *
     * @param registerRequestDTO 用户注册时提交的表单
     * @return
     */
    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<String> register(@RequestBody RegisterRequestDTO registerRequestDTO) {
        return authService.register(registerRequestDTO);
    }

    /**
     * 获取验证码
     * @param captchaDTO
     * @return
     */
    @Operation(summary = "获取验证码")
    @PostMapping("/captcha")
    public Result<String> getCaptcha(@RequestBody CaptchaDTO captchaDTO) {
        return authService.captcha(captchaDTO);
    }

    /**
     * 忘记密码
     * @param forgetPasswordDTO 忘记密码需要提交的表单
     * @return
     */
    @Operation(summary = "忘记密码")
    @PostMapping("/forget-password")
    public Result<String> forgetPassword(@RequestBody ForgetPasswordDTO forgetPasswordDTO) {
        return authService.forgetPassword(forgetPasswordDTO);
    }
}
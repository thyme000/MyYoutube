package cn.diaojiahong.myyoutube.login.service;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.login.controller.domain.dto.CaptchaDTO;
import cn.diaojiahong.myyoutube.login.controller.domain.dto.ForgetPasswordDTO;
import cn.diaojiahong.myyoutube.login.controller.domain.dto.LoginRequestDTO;
import cn.diaojiahong.myyoutube.login.controller.domain.dto.RegisterRequestDTO;

public interface IAuthService {

    Result<String> login(LoginRequestDTO loginRequestDTO);

    Result<String> register(RegisterRequestDTO registerRequestDTO);

    Result<String> captcha(CaptchaDTO captchaDTO);

    Result<String> forgetPassword(ForgetPasswordDTO forgetPasswordDTO);
}

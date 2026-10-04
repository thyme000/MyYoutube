package cn.diaojiahong.myyoutube.auth.service;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.auth.domain.dto.ForgetPasswordDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.LoginRequestDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.LoginResponseDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.RefreshTokenDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;

public interface IAuthService {

    LoginResponseDTO login(LoginRequestDTO loginRequestDTO);

    LoginResponseDTO refresh(RefreshTokenDTO refreshTokenDTO);

    void logout(RefreshTokenDTO refreshTokenDTO);

    void register(RegisterRequestDTO registerRequestDTO);

    void forgetPassword(ForgetPasswordDTO forgetPasswordDTO);
}

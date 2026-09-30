package cn.diaojiahong.myyoutube.auth.service;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.auth.domain.dto.ForgetPasswordDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.LoginRequestDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.LoginResponseDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.RefreshTokenDTO;
import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;

public interface IAuthService {

    Result<LoginResponseDTO> login(LoginRequestDTO loginRequestDTO);

    Result<LoginResponseDTO> refresh(RefreshTokenDTO refreshTokenDTO);

    Result<String> logout(RefreshTokenDTO refreshTokenDTO);

    Result<String> register(RegisterRequestDTO registerRequestDTO);

    Result<String> forgetPassword(ForgetPasswordDTO forgetPasswordDTO);
}

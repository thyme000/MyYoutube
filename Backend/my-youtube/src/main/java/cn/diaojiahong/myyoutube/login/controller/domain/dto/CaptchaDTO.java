package cn.diaojiahong.myyoutube.login.controller.domain.dto;

import lombok.Data;

/**
 * 获取验证码时传入
 * 用于区分是登录还是注册
 */
@Data
public class CaptchaDTO {
    private String username;
    private String phone;
}

package cn.diaojiahong.myyoutube.login.controller.domain.dto;

import lombok.Data;

@Data
public class ForgetPasswordDTO {
    // 用户名称
    private String username;
    // 手机号
    private String phone;
    // 验证码
    private String captcha;
    // 新密码
    private String newPassword;
}

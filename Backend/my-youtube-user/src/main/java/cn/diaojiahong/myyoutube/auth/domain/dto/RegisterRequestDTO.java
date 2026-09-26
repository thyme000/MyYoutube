package cn.diaojiahong.myyoutube.auth.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequestDTO {
    // 用户名称
    private String username;
    // 密码
    private String password;
    // 确认密码
    private String confirmPassword;
    // 邮箱
    private String email;
    // 手机号
    private String phone;
    // 验证码
    private String captcha;
}

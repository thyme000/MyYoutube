package cn.diaojiahong.myyoutube.auth.domain.dto;

import lombok.Data;

@Data
public class ForgetPasswordDTO {
    private String username;
    private String phone;
    private String newPassword;
}

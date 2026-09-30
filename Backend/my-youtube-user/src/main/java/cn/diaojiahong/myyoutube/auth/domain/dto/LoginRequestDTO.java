package cn.diaojiahong.myyoutube.auth.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequestDTO {
    // 用户名称
    private String username;

    // 用户密码
    private String password;

}

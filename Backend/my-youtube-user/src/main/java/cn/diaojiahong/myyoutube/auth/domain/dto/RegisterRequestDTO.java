package cn.diaojiahong.myyoutube.auth.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequestDTO {
    private String username;
    private String password;
    private String confirmPassword;
    private String email;
    private String phone;
    }

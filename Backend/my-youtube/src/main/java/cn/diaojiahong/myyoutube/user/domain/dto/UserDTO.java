package cn.diaojiahong.myyoutube.user.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {
    private Long id;
    private String nickname;
    private String avatarUrl;
    private String introduction;
}

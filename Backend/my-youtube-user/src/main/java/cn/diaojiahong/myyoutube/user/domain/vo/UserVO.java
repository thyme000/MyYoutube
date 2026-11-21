package cn.diaojiahong.myyoutube.user.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserVO {
    private Long id;
    private boolean blockedByMe;
    private String nickname;
    private String avatarUrl;
    private Long fans;
    private Long likeCount;
    private String bgImage;
    private Long attentionCount;
    private String introduction;
}

package cn.diaojiahong.myyoutube.user.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserVO {
    // 用户id
    private Long id;
    // 用户昵称
    private String nickname;
    // 用户头像url
    private String avatarUrl;
    // 用户粉丝数
    private Long fans;
    // 用户获得点赞数
    private Long likeCount;
    // 用户背景图片url
    private String bgImage;
    // 用户关注数
    private Long attentionCount;
    // 用户简介
    private String introduction;
}

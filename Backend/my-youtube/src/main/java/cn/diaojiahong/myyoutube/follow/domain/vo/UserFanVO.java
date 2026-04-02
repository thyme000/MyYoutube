package cn.diaojiahong.myyoutube.follow.domain.vo;

import lombok.Data;

@Data
public class UserFanVO {
    // 粉丝用户的id
    private Long fanId;
    // 头像url
    private String avatarUrl;
    // 昵称
    private String nickname;
    // 个人简介
    private String introduction;
}

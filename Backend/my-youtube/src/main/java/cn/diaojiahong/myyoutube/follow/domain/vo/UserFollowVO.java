package cn.diaojiahong.myyoutube.follow.domain.vo;

import lombok.Data;

@Data
public class UserFollowVO {
    // 被关注用户的id
    private Long followeeId;
    // 头像url
    private String authorAvatarUrl;
    // 昵称
    private String nickname;
    // 个人简介
    private String introduction;
}

package cn.diaojiahong.myyoutube.search.domain.vo;

import lombok.Data;

@Data
public class SearchUserVO {
    // 用户ID
    private Long id;
    // 用户昵称
    private String nickname;
    // 用户头像URL
    private String avatarUrl;
    // 用户简介
    private String introduction;
    // 粉丝数
    private Long fans;
    // 获赞数
    private Long likeCount;
}

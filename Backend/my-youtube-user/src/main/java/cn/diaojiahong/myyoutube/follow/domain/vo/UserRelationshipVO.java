package cn.diaojiahong.myyoutube.follow.domain.vo;

import lombok.Data;

@Data
public class UserRelationshipVO {
    private Long userId;
    private String avatarUrl;
    private String nickname;
    private String introduction;
}

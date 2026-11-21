package cn.diaojiahong.myyoutube.search.domain.vo;

import lombok.Data;

@Data
public class SearchUserVO {
    private Long id;
    private String nickname;
    private String avatarUrl;
    private String introduction;
    private Long fans;
    private Long likeCount;
    private Boolean isFollow;
}

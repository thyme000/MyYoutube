package cn.diaojiahong.myyoutube.search.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SearchVideoVO {
    private String id;
    private String title;
    private String description;
    private String coverUrl;
    private String videoUrl;
    private Integer duration;
    private Long likeCount;
    private Long playCount;
    private Long shareCount;
    private Long commentCount;
    private Integer allowComment;
    private Long authorId;
    private String authorNickname;
    private String authorAvatarUrl;
    private LocalDateTime createTime;
    private String tags;
    // 视频类型
    private Boolean isLike;
    private Boolean isFollow;
}

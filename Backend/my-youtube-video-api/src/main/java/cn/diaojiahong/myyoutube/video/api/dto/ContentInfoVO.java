package cn.diaojiahong.myyoutube.video.api.dto;

import lombok.Data;

@Data
public class ContentInfoVO {
    private String id;
    private String type;
    private String title;
    private String description;
    private String coverUrl;
    private Long likeCount;
    private Long playCount;
    private Long shareCount;
    private Long collectCount;
    private Long commentCount;
    private Integer allowComment;
    private Long authorId;
    private java.time.LocalDateTime createTime;
    private String tags;
    private String categoryCode;
    private String status;
    private String visibility;
    private String authorNickname;
    private String authorAvatarUrl;
}

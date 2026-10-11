package cn.diaojiahong.myyoutube.video.api.dto;

import lombok.Data;

@Data
public class ContentSummary {
    private Long id;
    private String type;
    private String title;
    private String description;
    private String coverUrl;
    private String videoUrl;
    private Long durationMs;
    private Long exportDurationMs;
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
}

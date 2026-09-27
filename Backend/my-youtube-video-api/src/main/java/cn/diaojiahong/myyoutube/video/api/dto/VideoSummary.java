package cn.diaojiahong.myyoutube.video.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频摘要, 跨服务传输用, 字段与 Video 对齐
 */
@Data
public class VideoSummary {
    private Long id;
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
    private LocalDateTime createTime;
    private String tags;
}

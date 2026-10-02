package cn.diaojiahong.myyoutube.video.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一内容摘要: 视频与动态的公共展示字段, 供搜索/推荐等消费方跨类型装配。
 */
@Data
public class ContentSummary {

    private Long id;

    /** video/dynamic */
    private String type;

    private String title;

    private String description;

    /** 封面 objectKey: 视频用封面, 动态用首帧 */
    private String coverUrl;

    /** 视频 objectKey, 动态为 null */
    private String videoUrl;

    /** 视频时长(秒), 动态为 null */
    private Integer duration;

    /** 动态成片总时长(毫秒), 视频为 null */
    private Integer totalDurationMs;

    private Long likeCount;

    private Long playCount;

    private Long shareCount;

    private Long commentCount;

    /** 是否允许评论(视频域), 动态恒为 1 */
    private Integer allowComment;

    private Long authorId;

    private LocalDateTime createTime;

    private String tags;
}

package cn.diaojiahong.myyoutube.content.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一内容卡片/消费流 VO: 视频与动态共用的消费端字段。
 * 动态行只带首帧封面与总时长, 时间轴明细由前端按需拉 /dynamics/{id}。
 */
@Data
public class ContentFeedVO {

    private String id;

    /** video/dynamic */
    private String type;

    private String title;

    private String description;

    /** 签名后的封面(视频=封面, 动态=首帧) */
    private String coverUrl;

    /** 签名后的视频地址, 动态为 null */
    private String videoUrl;

    /** 视频时长(秒), 动态为 null */
    private Integer duration;

    /** 动态成片总时长(毫秒), 视频为 null */
    private Integer totalDurationMs;

    private Long likeCount;

    private Long playCount;

    private Long commentCount;

    private Long shareCount;

    /** 审核状态(published/review/banned), 创作中心用 */
    private String status;

    /** 可见性(public/private/unlisted), 视频域; 动态恒 public */
    private String visibility;

    /** 是否允许评论(视频域开关); 动态恒允许 */
    private Integer allowComment;

    private Long authorId;

    private String authorNickname;

    private String authorAvatarUrl;

    private LocalDateTime createTime;

    private Boolean isLike;

    private Boolean isFollow;
}

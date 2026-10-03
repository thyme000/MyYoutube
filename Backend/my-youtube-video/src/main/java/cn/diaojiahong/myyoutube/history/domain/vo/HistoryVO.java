package cn.diaojiahong.myyoutube.history.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 浏览历史条目: 卡片字段与赞过列表同构(统一内容池混排), 额外携带观看时间
 */
@Data
public class HistoryVO {
    // 内容id
    private String id;

    /** 内容类型: video/dynamic(统一内容池) */
    private String type;

    /** 动态成片总时长(毫秒), 视频无 */
    private Integer totalDurationMs;
    // 标题
    private String title;
    // 描述
    private String description;
    // 封面url
    private String coverUrl;
    // 视频url
    private String videoUrl;
    // 视频时长
    private Integer duration;
    // 点赞量
    private Long likeCount;
    // 播放量
    private Long playCount;
    // 分享数
    private Long shareCount;
    // 评论数
    private Long commentCount;
    // 是否允许评论
    private Integer allowComment;
    // 作者id
    private Long authorId;
    // 作者昵称
    private String authorNickname;
    // 作者头像url
    private String authorAvatarUrl;
    // 内容创建时间
    private LocalDateTime createTime;
    // 标签
    private String tags;
    // 是否关注作者
    private Boolean isFollow;
    // 最近观看时间
    private LocalDateTime viewTime;
}

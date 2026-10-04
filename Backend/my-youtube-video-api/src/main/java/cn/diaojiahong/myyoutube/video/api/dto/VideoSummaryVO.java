package cn.diaojiahong.myyoutube.video.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频/动态摘要卡片公共字段, 各端展示 VO 的基类; 互动标志位(isLike/isFollow)与
 * 动态特有字段(type/totalDurationMs)由子类按场景补充
 */
@Data
public class VideoSummaryVO {
    // 内容id
    private String id;
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
    // 创建时间
    private LocalDateTime createTime;
    // 标签
    private String tags;
}

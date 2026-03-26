package cn.diaojiahong.myyoutube.video.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ShortsVO {
    // 视频id
    private String id;
    // 视频标题
    private String title;
    // 视频描述
    private String description;
    // 视频封面url
    private String coverUrl;
    // 视频url
    private String videoUrl;
    // 视频时长
    private Integer duration;
    // 视频点赞量
    private Long likeCount;
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
    // 视频创建时间
    private LocalDateTime createTime;
    // 视频标签
    private String tags;
}

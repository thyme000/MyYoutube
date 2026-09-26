package cn.diaojiahong.myyoutube.user.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户历史的短视频/长视频通用展示 VO, 字段与 video 模块的视频摘要对齐
 */
@Data
public class UserShortsVideoVO {
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
    private Boolean isFollow;
    private Boolean isLike;
}

package cn.diaojiahong.myyoutube.video.domain.vo;

import cn.diaojiahong.myyoutube.video.api.enums.VideoCategoryEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 视频列表 / 卡片展示：不包含正片地址、简介等，减少暴露字段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoListVO {

    // 视频id
    @JsonSerialize(using = ToStringSerializer.class)
    private String id;
    // 标题
    private String title;
    // 封面图 url
    private String coverUrl;
    // 时长（秒）
    private Integer duration;
    // 视频分类
    private VideoCategoryEnum category;
    // 播放量
    private Long playCount;
    // 点赞数
    private Long likeCount;
    // 评论数
    private Long commentCount;
    // 分享数
    private Long shareCount;
    // 创建时间
    private LocalDateTime createTime;
    // 视频url
    private String videoUrl;
    // 视频描述
    private String description;
    // 视频标签
    private String tags;
    // 是否允许评论
    private Boolean allowComment;
    // 是否点过赞
    private Boolean isLike;

    // 作者用户 id
    private Long authorId;
    // 作者昵称
    private String authorNickname;
    // 作者头像 url
    private String authorAvatarUrl;
    // 可见范围 public / private / unlisted
    private VideoVisibilityEnum visibility;
}

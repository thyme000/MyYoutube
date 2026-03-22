package cn.diaojiahong.myyoutube.video.domain.vo;

import cn.diaojiahong.myyoutube.video.domain.enums.VideoCategoryEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 视频详情：在列表基础上补充播放与互动相关字段（仍不含逻辑删除等内部字段）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoDetailVO {

    // 视频id
    private Long id;
    // 标题
    private String title;
    // 简介/描述
    private String description;
    // 封面图 url
    private String coverUrl;
    // 正片播放地址
    private String videoUrl;
    // 时长（秒）
    private Integer duration;
    // 视频形态 short / long
    private VideoTypeEnum videoType;
    // 视频分类
    private VideoCategoryEnum category;
    // 标签
    private String tags;
    // 播放量
    private Long playCount;
    // 点赞数
    private Long likeCount;
    // 收藏数
    private Long collectCount;
    // 评论数
    private Long commentCount;
    // 分享数
    private Long shareCount;
    // 可见范围
    private String visibility;
    // 是否允许评论 0/1
    private Integer allowComment;
    // 发布地区
    private String region;
    // 创建时间
    private LocalDateTime createTime;
    // 作者id
    private Long authorId;
    // 作者的昵称
    private String authorNickname;
    // 作者的粉丝数
    private Long authorFanCount;
    // 作者头像的url地址
    private String authorAvatarUrl;
}

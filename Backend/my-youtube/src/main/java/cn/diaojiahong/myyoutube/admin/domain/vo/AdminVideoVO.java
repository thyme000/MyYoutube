package cn.diaojiahong.myyoutube.admin.domain.vo;

import cn.diaojiahong.myyoutube.video.domain.enums.VideoCategoryEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminVideoVO {
    // 视频id
    private Long videoId;
    // 视频标题
    private String title;
    // 视频描述
    private String description;
    // 视频封面url
    private String coverUrl;
    // 视频url
    private String videoUrl;
    // 视频类型
    private String videoType;
    // 视频种类
    private VideoCategoryEnum videoCategory;
    // 视频创建时间
    private LocalDateTime createTime;
    // 作者id
    private Long authorId;
    // 作者昵称
    private String authorNickname;
    // 作者头像url
    private String authorAvatarUrl;
}

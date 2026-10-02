package cn.diaojiahong.myyoutube.notification.domain.vo;

import cn.diaojiahong.myyoutube.video.api.enums.NotificationTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationVO {
    private Long id;
    // 发送者id
    private Long senderId;
    // 发送者昵称
    private String senderNickname;
    // 发送者头像url
    private String senderAvatarUrl;
    // 类型
    private NotificationTypeEnum type;
    // 内容
    private String content;
    // 目标id
    private Long refId;
    // 视频id(统一内容池后为内容id)
    private Long videoId;
    // 关联内容类型: video/dynamic, 前端据此分流跳转
    private String refType;
    // 发送时间
    private LocalDateTime createTime;
    // 是否已读
    private Boolean isRead;
}

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
    private Long contentId;
    private String mediaType;
    private String refType;
    // 发送时间
    private LocalDateTime createTime;
    // 是否已读
    private Boolean isRead;
}

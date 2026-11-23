package cn.diaojiahong.myyoutube.notification.domain.vo;

import cn.diaojiahong.myyoutube.content.api.enums.NotificationTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationVO {
    private Long id;
    private Long senderId;
    private String senderNickname;
    private String senderAvatarUrl;
    private NotificationTypeEnum type;
    private String content;
    private Long refId;
    private Long contentId;
    private String mediaType;
    private String refType;
    private LocalDateTime createTime;
    private Boolean isRead;
}

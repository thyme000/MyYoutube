package cn.diaojiahong.myyoutube.notification.domain.vo;

import lombok.Data;

@Data
public class NotificationForJson {
    // 通知id
    private Long id;
    // 发送者昵称
    private String senderNickname;
    // 发送者头像url
    private String senderAvatarUrl;
    // 类型
    private String type;
    // 目标id
    private Long refId;
    // 视频id
    private Long videoId;
    // 内容
    private String content;
}

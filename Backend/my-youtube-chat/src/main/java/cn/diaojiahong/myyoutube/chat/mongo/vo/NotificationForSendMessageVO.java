package cn.diaojiahong.myyoutube.chat.mongo.vo;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ContentTypeEnum;
import lombok.Data;

@Data
public class NotificationForSendMessageVO {
    // 会话id
    private String conversationId;
    // 消息id
    private String messageId;
    // 消息类型
    private ContentTypeEnum contentType;
    // 消息内容
    private String content;
    // 发送人id
    private String senderId;
    // 发送人昵称
    private String senderNickname;
    // 发送人头像url 或 群聊头像url
    private String avatarUrl;
}

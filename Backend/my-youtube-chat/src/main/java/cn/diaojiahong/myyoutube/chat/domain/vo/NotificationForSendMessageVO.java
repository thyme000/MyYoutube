package cn.diaojiahong.myyoutube.chat.domain.vo;

import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import lombok.Data;

@Data
public class NotificationForSendMessageVO {
    private String conversationId;
    private String messageId;
    private Long seq;
    private ChatContentTypeEnum contentType;
    private String content;
    private String senderId;
    private String senderNickname;
    private String avatarUrl;
}

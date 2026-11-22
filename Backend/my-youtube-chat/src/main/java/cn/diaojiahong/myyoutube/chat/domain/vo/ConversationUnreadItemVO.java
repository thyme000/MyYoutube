package cn.diaojiahong.myyoutube.chat.domain.vo;

import lombok.Data;

@Data
public class ConversationUnreadItemVO {
    private String conversationId;
    private Long lastSeq;
    private Long unreadCount;
}

package cn.diaojiahong.myyoutube.chat.domain.dto;

import lombok.Data;

@Data
public class ConversationUnreadRow {
    private String conversationId;
    private Long lastSeq;
    private Long lastReadSeq;
}

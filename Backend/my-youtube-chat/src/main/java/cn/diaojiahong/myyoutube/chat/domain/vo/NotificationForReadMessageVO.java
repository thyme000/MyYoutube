package cn.diaojiahong.myyoutube.chat.domain.vo;

import lombok.Data;

@Data
public class NotificationForReadMessageVO {
    private String conversationId;
    private Long lastReadSequence;
}

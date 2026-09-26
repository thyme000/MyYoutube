package cn.diaojiahong.myyoutube.chat.mongo.vo;

import lombok.Data;

@Data
public class NotificationForReadMessageVO {
    // 会话id
    private String conversationId;
    // 最后已读的序列
    private Long lastReadSequence;
}

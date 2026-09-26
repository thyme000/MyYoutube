package cn.diaojiahong.myyoutube.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReadMessageMessage {
    // 会话id
    private String conversationId;
    // 接收方id
    private String receiverId;
    // 最后已读序列
    private Long lastReadSeq;
}

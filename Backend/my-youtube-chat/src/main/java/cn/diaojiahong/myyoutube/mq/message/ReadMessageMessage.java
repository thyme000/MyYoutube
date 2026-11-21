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
    public static final String TOPIC = "chat_read_topic";

    private String conversationId;
    private String receiverId;
    private Long lastReadSeq;
}

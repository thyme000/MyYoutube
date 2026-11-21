package cn.diaojiahong.myyoutube.mq.message;

import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.RecallTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageMessage {
    public static final String TOPIC = "chat_message_topic";

    private String conversationId;
    private String messageId;
    private Long senderId;
    private ChatContentTypeEnum contentType;

    // 消息内容(只有文本消息时才有值)
    private String content;
    private Long seq;

    // 发生的事件(send/withdrawn)
    private String event;
    private LocalDateTime serviceTime;
    private RecallTypeEnum recallType;
}

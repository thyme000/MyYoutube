package cn.diaojiahong.myyoutube.mq.message;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.RecallTypeEnum;
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
    // 会话id
    private String conversationId;
    // 消息id
    private String messageId;
    // 发送人id
    private Long senderId;
    // 消息类型
    private ChatContentTypeEnum contentType;

    // 消息内容(只有文本消息时才有值)
    private String content;
    // 递增序号
    private Long seq;

    // 发生的事件(send/withdrawn)
    private String event;
    // 服务时间
    private LocalDateTime serviceTime;
    // 撤回类型
    private RecallTypeEnum recallType;
}

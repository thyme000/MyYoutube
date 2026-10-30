package cn.diaojiahong.myyoutube.chat.mongo.dto.ack;

import cn.diaojiahong.myyoutube.chat.mongo.enums.MessageStatusEnum;
import lombok.Data;

@Data
public class ChatMessageAckDTO {
    // 消息id
    private String messageId;
    // 会话id
    private String conversationId;

    // 客户端幂等id(防重发)
    private String clientMsgId;

    // 服务端生成(会话内递增序号)
    private Long seq;
    // 服务端时间戳
    private Long serverTime;
    // 消息状态
    private MessageStatusEnum status;
}

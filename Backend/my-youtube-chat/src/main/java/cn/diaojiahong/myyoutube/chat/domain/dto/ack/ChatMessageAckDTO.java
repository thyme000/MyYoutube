package cn.diaojiahong.myyoutube.chat.domain.dto.ack;

import cn.diaojiahong.myyoutube.chat.domain.enums.MessageStatusEnum;
import lombok.Data;

@Data
public class ChatMessageAckDTO {
    private String messageId;
    private String conversationId;

    // 客户端幂等id(防重发)
    private String clientMsgId;

    // 服务端生成(会话内递增序号)
    private Long seq;
    private Long serverTime;
    private MessageStatusEnum status;
}

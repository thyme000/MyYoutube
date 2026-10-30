package cn.diaojiahong.myyoutube.chat.mongo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatMessageWithdrawDTO {
    // 会话id
    @NotBlank(message = "conversationId不能为空")
    private String conversationId;
    // 发送人id
    @NotNull(message = "senderId不能为空")
    private Long senderId;

    // 幂等id(防重发)
    @NotBlank(message = "clientMsgId不能为空")
    private String clientMsgId;
}

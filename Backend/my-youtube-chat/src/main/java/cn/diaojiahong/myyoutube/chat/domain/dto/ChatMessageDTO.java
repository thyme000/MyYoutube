package cn.diaojiahong.myyoutube.chat.domain.dto;

import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.ChatContent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatMessageDTO {
    // 按会话发送，与 peerUserId 二选一。
    private String conversationId;
    // 按用户发送私聊。已有私聊则复用，首次发送成功时才创建。
    private Long peerUserId;
    @NotNull(message = "contentType不能为空")
    private ChatContentTypeEnum contentType;
    @NotNull(message = "content不能为空")
    private ChatContent content;

    // 幂等id(防重发)
    @NotBlank(message = "clientMsgId 不能为空")
    @Size(max = 128, message = "clientMsgId 长度不能超过 128")  
    private String clientMsgId;
}

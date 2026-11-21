package cn.diaojiahong.myyoutube.chat.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatMessageWithdrawDTO {
    // 要撤回的消息主键
    @NotBlank(message = "messageId不能为空")
    private String messageId;
}

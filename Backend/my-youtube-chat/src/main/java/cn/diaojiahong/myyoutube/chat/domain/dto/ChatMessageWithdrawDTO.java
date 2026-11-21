package cn.diaojiahong.myyoutube.chat.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatMessageWithdrawDTO {
    @NotBlank(message = "messageId不能为空")
    private String messageId;
}

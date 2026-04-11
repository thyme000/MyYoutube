package cn.diaojiahong.myyoutube.chat.mongo.dto;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateConversationDTO {
    // 会话类型：single（私聊）、group（群聊）
    @NotNull
    private ConversationTypeEnum type;
    // 成员用户id列表
    @NotNull
    private List<Long> memberIds;
    // 群名称（私聊为对方昵称）
    @NotBlank
    private String name;
    // 群头像 URL（私聊为对方头像）
    @NotBlank
    private String avatar;
}

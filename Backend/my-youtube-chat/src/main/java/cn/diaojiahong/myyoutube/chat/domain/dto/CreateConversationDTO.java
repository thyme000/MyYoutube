package cn.diaojiahong.myyoutube.chat.domain.dto;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateConversationDTO {
    // 只用于创建群聊。私聊由第一条消息创建。
    @NotNull
    private ConversationTypeEnum type;
    // 成员用户id列表
    @NotNull
    private List<Long> memberIds;
    // 群名称
    @Size(max = Conversation.NAME_MAX_LENGTH, message = "群名称不能超过{max}个字")
    private String name;
    // 群头像对象键
    private String avatar;
}

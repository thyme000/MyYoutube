package cn.diaojiahong.myyoutube.chat.domain.vo;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import lombok.Data;

@Data
public class ValidUserConversationVO {
    private String conversationId;
    private String conversationName;
    private ConversationTypeEnum conversationType;
    private String conversationAvatar;
}

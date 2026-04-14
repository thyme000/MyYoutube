package cn.diaojiahong.myyoutube.chat.mongo.vo;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import lombok.Data;

@Data
public class ValidUserConversationVO {
    // 会话id
    private String conversationId;
    // 会话名称
    private String conversationName;
    // 会话类型
    private ConversationTypeEnum conversationType;
    // 会话头像
    private String conversationAvatar;
}

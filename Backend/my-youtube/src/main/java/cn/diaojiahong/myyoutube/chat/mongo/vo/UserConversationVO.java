package cn.diaojiahong.myyoutube.chat.mongo.vo;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserConversationVO {
    // 会话id
    private String conversationId;
    // 会话名称
    private String conversationName;
    // 会话类型
    private ConversationTypeEnum conversationType;
    // 会话头像
    private String conversationAvatar;
    // 会话的最后消息是否撤回
    private Boolean isLastMessageRecall;
    // 会话最后消息时间
    private LocalDateTime lastMessageTime;
    // 会话最后消息内容
    private String lastMessageContent;
    // 会话最后消息发送人昵称
    private String lastMessageSenderNickname;
    // 是否置顶
    private Boolean pin;
    // 是否免打扰
    private Boolean mute;
    // 最后已读序列
    private Long lastReadSeq;
    // 未读数
    private Integer unreadCount;
    // 是否被禁言
    private Boolean banned;
}

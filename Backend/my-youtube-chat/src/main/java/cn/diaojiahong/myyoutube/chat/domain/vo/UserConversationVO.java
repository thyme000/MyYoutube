package cn.diaojiahong.myyoutube.chat.domain.vo;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserConversationVO {
    private String conversationId;
    private String conversationName;
    private ConversationTypeEnum conversationType;
    private ConversationStatus status;
    // 当前用户是否拉黑了私聊对方。
    private Boolean blockedByMe;
    private String conversationAvatar;
    private Boolean isLastMessageRecall;
    private LocalDateTime lastMessageTime;
    private String lastMessageContent;
    private String lastMessageSenderNickname;
    private Boolean pin;
    private Boolean mute;
    private Long lastReadSeq;
    private Long unreadCount;
    private Boolean banned;
    private Boolean conversationBanned;
    private Long createdBy;
    private Integer lastAnnouncementVersion;
    private Integer lastSeenAnnouncementVersion;
}

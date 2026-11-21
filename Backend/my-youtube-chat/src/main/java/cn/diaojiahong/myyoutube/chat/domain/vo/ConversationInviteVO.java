package cn.diaojiahong.myyoutube.chat.domain.vo;

import cn.diaojiahong.myyoutube.chat.domain.enums.InviteStatusEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationInviteVO {

    private String id;

    private String conversationId;

    private String conversationName;

    private Long inviterId;

    private String inviterNickname;

    private InviteStatusEnum status;

    private LocalDateTime createdAt;
}

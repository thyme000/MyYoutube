package cn.diaojiahong.myyoutube.chat.domain.vo;

import cn.diaojiahong.myyoutube.chat.domain.enums.RecallTypeEnum;
import lombok.Data;

@Data
public class NotificationForWithdrawMessageVO {
    private String conversationId;
    private String messageId;
    private String senderId;
    private String senderNickname;
    private String avatarUrl;
    private RecallTypeEnum recallType;
}

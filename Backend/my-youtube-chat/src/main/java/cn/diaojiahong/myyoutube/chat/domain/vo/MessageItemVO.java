package cn.diaojiahong.myyoutube.chat.domain.vo;

import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.RecallTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.ChatContent;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageItemVO {
    private Long senderId;
    private String senderNickName;
    private String senderAvatarUrl;
    private ChatContentTypeEnum contentType;
    private ChatContent content;
    private String messageId;
    private String clientMsgId;
    private Long seq;
    private MessageStatusEnum status;
    private LocalDateTime createdAt;
    private RecallTypeEnum recallType;
}

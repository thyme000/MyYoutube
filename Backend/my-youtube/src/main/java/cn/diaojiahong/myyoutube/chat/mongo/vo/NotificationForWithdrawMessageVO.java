package cn.diaojiahong.myyoutube.chat.mongo.vo;

import cn.diaojiahong.myyoutube.chat.mongo.enums.RecallTypeEnum;
import lombok.Data;

@Data
public class NotificationForWithdrawMessageVO {
    // 会话id
    private String conversationId;
    // 消息id
    private String messageId;
    // 发送人id
    private String senderId;
    // 发送人昵称
    private String senderNickname;
    // 群聊头像url
    private String avatarUrl;
    // 撤回类型
    private RecallTypeEnum recallType;
}

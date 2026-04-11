package cn.diaojiahong.myyoutube.chat.mongo.vo;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatContent;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessagePage {
    // 发送人id
    private Long senderId;
    // 发送人昵称
    private String senderNickName;
    // 发送人头像url
    private String senderAvatarUrl;
    // 消息类型
    private ContentTypeEnum contentType;
    // 消息内容
    private ChatContent content;
    // 消息id
    private String messageId;
    // 幂等id
    private String clientMsgId;
    // 序列号
    private Long seq;
    // 消息状态
    private MessageStatusEnum status;
    // 消息创建时间
    private LocalDateTime createdAt;
}

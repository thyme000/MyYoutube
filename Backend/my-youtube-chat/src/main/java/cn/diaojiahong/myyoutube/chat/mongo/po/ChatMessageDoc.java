package cn.diaojiahong.myyoutube.chat.mongo.po;

import cn.diaojiahong.myyoutube.chat.mongo.base.BaseMongoEntity;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.RecallTypeEnum;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Document("chat_messages")
public class ChatMessageDoc extends BaseMongoEntity {

    // 消息id
    @Id
    private String id;

    // 会话id
    private String conversationId;
    // 发送者id
    private Long senderId;
    // 消息类型
    private ChatContentTypeEnum contentType; // text/image/file/system
    // 消息体
    private ChatContent content;

    // 客户端幂等id(防重发)
    private String clientMsgId;
    // 会话内递增序号
    private Long seq;
    // 消息状态
    private MessageStatusEnum status;
    // 是否撤回
    private Boolean recalled;
    // 撤回时间
    private LocalDateTime recalledAt;

    // 撤回类型(1:群主撤回,2:成员撤回)
    private RecallTypeEnum recallType;
}

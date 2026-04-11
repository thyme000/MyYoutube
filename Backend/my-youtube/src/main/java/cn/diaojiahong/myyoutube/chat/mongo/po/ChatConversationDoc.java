package cn.diaojiahong.myyoutube.chat.mongo.po;

import cn.diaojiahong.myyoutube.chat.mongo.base.BaseMongoEntity;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Document("chat_conversations")
public class ChatConversationDoc extends BaseMongoEntity {
    /** 会话主键，与消息里的 conversationId 对应 */
    @Id
    private String id;
    /** 会话类型：single（私聊）、group（群聊） */
    private ConversationTypeEnum type;
    /** 成员用户 ID 列表 */
    private List<Long> memberIds;
    /** 群名称（私聊可为 null） */
    private String name;
    /** 群头像 URL（私聊可为 null） */
    private String avatar;
    /** 最后一条消息 ID */
    private String lastMessageId;
    /** 最后一条消息摘要（列表展示） */
    private String lastMessagePreview;
    /** 最后一条消息发送人昵称 */
    private String lastMessageSenderNickname;
    /** 最后消息时间 */
    private LocalDateTime lastMessageAt;
    /** 最后一条消息是否撤回 */
    private Boolean isLastMessageRecall;
    /** 创建人用户 ID */
    private Long createdBy;
    /** 是否开启全员禁言(群才有) */
    private Boolean banned;
}

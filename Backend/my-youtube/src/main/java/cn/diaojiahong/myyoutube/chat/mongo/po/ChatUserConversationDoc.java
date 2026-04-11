package cn.diaojiahong.myyoutube.chat.mongo.po;

import cn.diaojiahong.myyoutube.chat.mongo.base.BaseMongoEntity;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document("chat_user_conversation")
public class ChatUserConversationDoc extends BaseMongoEntity {
    @Id
    private String id;
    /** 用户 ID */
    private Long userId;
    /** 会话 ID（对应 ChatConversationDoc.id） */
    private String conversationId;
    /** 未读条数 */
    private Integer unreadCount;
    /** 最后已读消息序号（与 ChatMessageDoc.seq 对齐） */
    private Long lastReadSeq;
    /** 是否免打扰 */
    private Boolean mute;
    /** 是否置顶 */
    private Boolean pin;
    /** 是否在会话列表中软删除 */
    private Boolean deleted;
    /** 上次客户端同步时间（可选，用于增量拉取） */
    private LocalDateTime lastPullAt;
    /** 是否被禁言 */
    private Boolean banned;
}

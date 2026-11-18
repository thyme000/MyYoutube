package cn.diaojiahong.myyoutube.chat.mongo.po;

import cn.diaojiahong.myyoutube.chat.mongo.base.BaseMongoEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("chat_user_conversation")
public class ChatUserConversationDoc extends BaseMongoEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private String conversationId;
    private Integer unreadCount;
    private Long lastReadSeq;
    private Boolean mute;
    private Boolean pin;
    private Boolean deleted;
    private LocalDateTime lastPullAt;
    private Boolean banned;
    private Integer lastSeenAnnouncementVersion;
}

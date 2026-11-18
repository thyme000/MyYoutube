package cn.diaojiahong.myyoutube.chat.mongo.po;

import cn.diaojiahong.myyoutube.chat.mongo.base.BaseMongoEntity;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "chat_conversations", autoResultMap = true)
public class ChatConversationDoc extends BaseMongoEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private ConversationTypeEnum type;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> memberIds;

    private String name;
    private String avatar;
    private String lastMessageId;
    private String lastMessagePreview;
    private String lastMessageSenderNickname;
    private LocalDateTime lastMessageAt;

    @TableField("is_last_message_recall")
    private Boolean isLastMessageRecall;

    private Long createdBy;
    private Boolean banned;
    private String announcement;
    private Integer announcementVersion;
}

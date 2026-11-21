package cn.diaojiahong.myyoutube.chat.domain.po;

import cn.diaojiahong.myyoutube.chat.domain.base.TimedEntity;
import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.RecallTypeEnum;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "message", autoResultMap = true)
public class Message extends TimedEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String conversationId;
    private Long senderId;
    private ChatContentTypeEnum contentType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private ChatContent content;

    private String clientMsgId;
    private Long seq;
    private MessageStatusEnum status;
    private Boolean recalled;
    private LocalDateTime recalledAt;
    private RecallTypeEnum recallType;
}

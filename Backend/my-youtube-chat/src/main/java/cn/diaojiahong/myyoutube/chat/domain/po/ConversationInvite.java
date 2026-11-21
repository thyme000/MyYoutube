package cn.diaojiahong.myyoutube.chat.domain.po;

import cn.diaojiahong.myyoutube.chat.domain.base.TimedEntity;
import cn.diaojiahong.myyoutube.chat.domain.enums.InviteStatusEnum;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一条群邀请。同一人在同一群里只有一行，再次邀请时改状态，不另插。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("conversation_invite")
public class ConversationInvite extends TimedEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String conversationId;

    private Long inviterId;

    private Long inviteeId;

    private InviteStatusEnum status;
}

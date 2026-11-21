package cn.diaojiahong.myyoutube.chat.domain.po;

import cn.diaojiahong.myyoutube.chat.domain.base.TimedEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("conversation_member")
public class ConversationMember extends TimedEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private String conversationId;
    private Long lastReadSeq;
    /** 只显示序号大于本值的消息。入群时冻在当时的最后一条上，之后不再前移。 */
    private Long visibleAfterSeq;
    private String lastMessageId;
    private String lastMessagePreview;
    private String lastMessageSenderNickname;
    private LocalDateTime lastMessageAt;

    @TableField("is_last_message_recall")
    private Boolean isLastMessageRecall;

    private Boolean mute;
    private Boolean pin;
    private Boolean deleted;
    private Boolean inboxVisible;
    private Boolean banned;
    private Integer lastSeenAnnouncementVersion;

    /** 列表查询带出的排序时间，不是成员表的列。 */
    @TableField(exist = false)
    private LocalDateTime activityAt;

    public static ConversationMember newRow(Long memberId, String conversationId, LocalDateTime now) {
        ConversationMember member = new ConversationMember();
        member.setId(IdWorker.getIdStr());
        member.setUserId(memberId);
        member.setConversationId(conversationId);
        member.setLastReadSeq(0L);
        member.setVisibleAfterSeq(0L);
        member.setMute(false);
        member.setPin(false);
        member.setDeleted(false);
        member.setInboxVisible(true);
        member.setBanned(false);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        return member;
    }
}

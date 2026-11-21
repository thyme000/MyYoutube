package cn.diaojiahong.myyoutube.chat.domain.po;

import cn.diaojiahong.myyoutube.chat.domain.base.TimedEntity;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("conversation")
public class Conversation extends TimedEntity {

    /** 群名称最长字数。私聊不使用这一列。schema.sql 里 conversation.name 的长度与此相同。 */
    public static final int NAME_MAX_LENGTH = 32;

    public static String invalidNameMessage() {
        return "群名称不能为空，且不超过" + NAME_MAX_LENGTH + "个字";
    }

    /** 私聊还没建立时，先开口的人不能再发下一条。 */
    public static final String WAITING_FOR_REPLY = "对方还没回复，暂时只能发送一条消息";
    public static final String PRIVATE_MESSAGE_UNAVAILABLE = "暂时无法向该用户发送私聊消息";

    @TableId(type = IdType.INPUT)
    private String id;
    private ConversationTypeEnum type;
    private ConversationStatus status;
    private String name;
    private String avatar;
    private Long lastSeq;
    /** 私聊是第一条消息的发送人。群聊是群主。 */
    private Long createdBy;
    /** 私聊双方按大小排位的用户 ID, 唯一键保证每对用户只有一个私聊; 群聊为空。 */
    private Long userA;
    private Long userB;
    private Boolean banned;
    private String announcement;
    private Integer announcementVersion;

    /** 空私聊（还没人发过消息）和等待回复的私聊都要准入裁决: 首条定发送人, 对方回复激活。 */
    public boolean requiresPrivateAdmission() {
        return type == ConversationTypeEnum.SINGLE
                && (createdBy == null || status == ConversationStatus.WAITING_REPLY);
    }

    /** 私聊对端按 user_a/user_b 反推: 对方可能还没打开过会话, 成员表里未必有他的行。 */
    public Long peerOf(Long userId) {
        return userA.equals(userId) ? userB : userA;
    }
}

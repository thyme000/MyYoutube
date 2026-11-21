package cn.diaojiahong.myyoutube.chat.mapper;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.time.LocalDateTime;

public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 按排序后的双方用户 ID 点查私聊。唯一键 uk_private_pair 保证一对一,
     * 成员从自己的列表删除后仍可匹配 (pair 列在会话创建时写死)。
     */
    Conversation findPrivateChat(Long userA, Long userB);

    /**
     * 同一行上把序号加一。UPDATE 的行锁把并发递增串行化，
     * 同一事务里随后用 selectLastSeq 回读即可。列表摘要写在成员行上，不放在这里。
     */
    int advanceOnMessage(String conversationId, LocalDateTime messageAt);

    /**
     * 锁住会话行，供写入前重新确认会话状态和首条消息的发送人。
     */
    Conversation lockById(String conversationId);

    int activatePrivateChat(String conversationId);

    /** 空私聊的第一条消息: 回填发送人与会话状态。last_seq=0 防止覆盖已有消息的会话。 */
    int startPrivateChat(String conversationId, Long senderId, ConversationStatus status);

    /** 按主键回读会话当前序号，供递增后在同一事务内取号。 */
    long selectLastSeq(String conversationId);

    /**
     * 发布公告并在数据库内原子递增版本: 同一群主双击/多端同时发布时,
     * 读旧值再加一再写回会撞出两条同版本号的公告, 成员的"公告已读"判断随之失效。
     */
    int publishAnnouncement(String conversationId,
                           String announcement,
                           LocalDateTime publishedAt);

}

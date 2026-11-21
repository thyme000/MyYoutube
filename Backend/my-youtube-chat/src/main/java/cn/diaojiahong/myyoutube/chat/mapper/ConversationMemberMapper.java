package cn.diaojiahong.myyoutube.chat.mapper;

import cn.diaojiahong.myyoutube.chat.domain.dto.ConversationListCursor;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.time.LocalDateTime;
import java.util.List;

public interface ConversationMemberMapper extends BaseMapper<ConversationMember> {

    /**
     * 发送方把自己的已读水位推到刚发出的序号。水位只升不降。
     */
    int advanceReadSeq(String conversationId, Long userId, long seq, LocalDateTime now);

    /**
     * 已读水位只升不降，并且不超过调用时看到的会话最大序号。只锁成员行，和发消息的加锁顺序错开。
     */
    int markRead(String conversationId, Long userId, long requestedSeq, long maxSeq, LocalDateTime now);

    int clearHistory(String conversationId, Long userId, long seq, LocalDateTime now);

    long sumUnread(Long userId);

    List<Long> selectActiveUserIds(String conversationId);

    /**
     * 置顶优先，其次按这个人自己的最后一条时间，没有消息则用入群时间。
     * cursor 为空表示第一页。sendableOnly 时排除自己被禁言的会话，以及别人创建的全员禁言群。
     */
    List<ConversationMember> listByActivity(Long userId, ConversationListCursor cursor, boolean sendableOnly, int limit);

    /**
     * 把仍在会话里的成员的列表尾巴改成这一条。已退出的人不改。
     */
    int applyTail(String conversationId,
                  String messageId,
                  LocalDateTime messageAt,
                  String preview,
                  String senderNickname);

    /**
     * 只标记尾巴正好是这条消息的成员。别人的列表摘要不动。
     */
    int markTailRecalled(String conversationId, String messageId, LocalDateTime recalledAt);
}

package cn.diaojiahong.myyoutube.chat.mapper;

import cn.diaojiahong.myyoutube.chat.domain.dto.ConversationListCursor;
import cn.diaojiahong.myyoutube.chat.domain.dto.ConversationUnreadRow;
import cn.diaojiahong.myyoutube.chat.domain.dto.PrivateInboxHideState;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.time.LocalDateTime;
import java.util.List;

public interface ConversationMemberMapper extends BaseMapper<ConversationMember> {

    /**
     * 已读水位只升不降，并且不超过调用时看到的会话最大序号。只锁成员行，和发消息的加锁顺序错开。
     */
    int markRead(String conversationId, Long userId, long requestedSeq, long maxSeq, LocalDateTime now);

    int clearHistory(String conversationId, Long userId, long seq, LocalDateTime now);

    /**
     * 隐藏前读到的会话序号、成员行和尾消息。普通查询，不加行锁。
     */
    PrivateInboxHideState findInboxHideState(String conversationId, Long userId);

    /**
     * 按成员行隐藏收件箱，并把已读水位推到调用方读到的序号，只进不退。
     * 成员未删除、当前仍可见，且尾消息与读到的值空值安全相等时才更新。
     */
    int hideInbox(String memberId, String lastMessageId, long lastSeq, LocalDateTime now);

    List<ConversationUnreadRow> listUnreadRows(Long userId);

    ConversationUnreadRow findMemberUnreadRow(Long userId, String conversationId);

    /**
     * 置顶优先，其次按这个人自己的最后一条时间，没有消息则用入群时间。
     * cursor 为空表示第一页。sendableOnly 时排除自己被禁言的会话，以及别人创建的全员禁言群。
     */
    List<ConversationMember> listByActivity(Long userId, ConversationListCursor cursor, boolean sendableOnly, int limit);

    /**
     * 仍在会话中的成员。只看 deleted = false，按成员行 id 升序。
     * 禁言和收件箱可见性由各自的业务判断。
     */
    default List<Long> selectActiveUserIds(String conversationId) {
        return selectList(new LambdaQueryWrapper<ConversationMember>()
                        .select(ConversationMember::getUserId)
                        .eq(ConversationMember::getConversationId, conversationId)
                        .eq(ConversationMember::getDeleted, false)
                        .orderByAsc(ConversationMember::getId))
                .stream()
                .map(ConversationMember::getUserId)
                .toList();
    }
}

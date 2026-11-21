package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PrivateConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper conversationMemberMapper;
    private final UserPort userPort;
    private final UserBlockPort blocks;

    /** 查自己与对方的正式私聊, 与参数顺序无关。 */
    Conversation findPrivateChat(Long userId, Long peerId) {
        return conversationMapper.findPrivateChat(Math.min(userId, peerId), Math.max(userId, peerId));
    }

    void validatePrivatePeer(Long peerId) {
        UserSummary peer = userPort.getUser(peerId);
        if (peer == null || peer.deleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "对方用户不存在");
        }
    }

    Conversation openOrReuse(Long userId, Long peerId) {
        Conversation existing = findPrivateChat(userId, peerId);
        if (existing == null) {
            validatePrivatePeer(peerId);
            if (blocks.blocksPrivateChat(userId, peerId)) {
                throw new BizException(ErrorCode.FORBIDDEN, Conversation.PRIVATE_MESSAGE_UNAVAILABLE);
            }
            try {
                existing = insertEmptyPrivateConversation(userId, peerId, LocalDateTime.now());
            } catch (DuplicateKeyException duplicate) {
                // 并发打开或并发首发已建了会话: 回查复用同一个 conversationId
                existing = findPrivateChat(userId, peerId);
                if (existing == null) {
                    throw duplicate;
                }
            }
        }
        ensurePrivateMember(existing.getId(), userId, LocalDateTime.now());
        return existing;
    }

    /**
     * 打开「聊一聊」时创建空私聊: last_seq=0、created_by 为空, 只有打开者一条成员行,
     * 对方的列表和只读查询都看不到。uk_private_pair 兜底双方并发打开只留一个会话。
     */
    Conversation insertEmptyPrivateConversation(Long userId, Long peerId, LocalDateTime now) {
        Conversation conversation = new Conversation();
        conversation.setId(IdWorker.getIdStr());
        conversation.setType(ConversationTypeEnum.SINGLE);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setLastSeq(0L);
        conversation.setUserA(Math.min(userId, peerId));
        conversation.setUserB(Math.max(userId, peerId));
        conversation.setBanned(false);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        conversationMemberMapper.insert(ConversationMember.newRow(userId, conversation.getId(), now));
        return conversation;
    }

    /**
     * inboxVisible=false 的一档给「发送人还没打开过会话」的补行用: 发送失败时残留的行
     * 不进任何人的列表, 发送成功后由 applyTail 置可见, 等价于随消息一起生效。
     */
    void ensurePrivateMember(String conversationId, Long userId, LocalDateTime now) {
        ensurePrivateMember(conversationId, userId, true, now);
    }

    /**
     * 补齐/恢复一个人在私聊里的成员行: 没有就插入, 从列表删过就恢复可见。
     * 已有的行直接复用, 不重置已读水位。uk_conversation_member 兜底并发补行。
     */
    void ensurePrivateMember(String conversationId, Long userId, boolean inboxVisible, LocalDateTime now) {
        ConversationMember existing = conversationMemberMapper.selectOne(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .eq(ConversationMember::getUserId, userId));
        if (existing == null) {
            ConversationMember member = ConversationMember.newRow(userId, conversationId, now);
            member.setInboxVisible(inboxVisible);
            try {
                conversationMemberMapper.insert(member);
            } catch (DuplicateKeyException duplicate) {
                // 对方(或首发事务)正在补同一行, 复用即可
            }
            return;
        }
        if (!inboxVisible || Boolean.TRUE.equals(existing.getInboxVisible())) {
            return;
        }
        conversationMemberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .eq(ConversationMember::getId, existing.getId())
                .eq(ConversationMember::getInboxVisible, false)
                .set(ConversationMember::getInboxVisible, true)
                .set(ConversationMember::getUpdatedAt, now));
    }

    /**
     * 双方都还没打开过时的首条消息: 在同一事务里建正式私聊并写入双方成员行。
     * uk_private_pair 兜底与「打开空会话」或另一方首发并发时只留一个会话。
     */
    Conversation insertPrivateConversation(Long senderId, Long peerId, ConversationStatus status,
                                           LocalDateTime now) {
        Conversation conversation = new Conversation();
        conversation.setId(IdWorker.getIdStr());
        conversation.setType(ConversationTypeEnum.SINGLE);
        conversation.setStatus(status);
        conversation.setLastSeq(0L);
        conversation.setCreatedBy(senderId);
        conversation.setUserA(Math.min(senderId, peerId));
        conversation.setUserB(Math.max(senderId, peerId));
        conversation.setBanned(false);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        conversationMemberMapper.insert(ConversationMember.newRow(senderId, conversation.getId(), now));
        conversationMemberMapper.insert(ConversationMember.newRow(peerId, conversation.getId(), now));
        return conversation;
    }

    void admitPrivateConversation(Conversation locked, Long senderId, boolean mutual) {
        Long initiatorId = locked.getCreatedBy();
        if (initiatorId == null) {
            // 空私聊的第一条消息: 谁先发谁就是首发人, 并补齐对方可能缺失的成员行
            ConversationStatus status = ConversationStatus.afterFirstPrivateMessage(mutual);
            conversationMapper.startPrivateChat(locked.getId(), senderId, status);
            ensurePrivateMember(locked.getId(), locked.peerOf(senderId), LocalDateTime.now());
            return;
        }

        if (locked.getStatus() == ConversationStatus.WAITING_REPLY) {
            if (senderId.equals(initiatorId) && !mutual) {
                throw new BizException(ErrorCode.STATE_CONFLICT, Conversation.WAITING_FOR_REPLY);
            }
            conversationMapper.activatePrivateChat(locked.getId());
        }
    }

    void setPrivateInboxVisible(String conversationId, Long userId, boolean visible) {
        conversationMemberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId)
                .eq(ConversationMember::getInboxVisible, !visible)
                .set(ConversationMember::getInboxVisible, visible)
                .set(ConversationMember::getUpdatedAt, LocalDateTime.now()));
    }
}

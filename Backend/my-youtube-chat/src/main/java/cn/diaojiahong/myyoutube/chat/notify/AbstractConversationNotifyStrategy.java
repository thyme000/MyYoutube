package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.domain.enums.ChatEventType;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.domain.vo.NotificationForSendMessageVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.NotificationForWithdrawMessageVO;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通知策略共享语义: 报文构建、免打扰查询、成员推送。未读在发消息事务里已经写完。
 */
@RequiredArgsConstructor
public abstract class AbstractConversationNotifyStrategy implements ConversationNotifyStrategy {

    private static final long AVATAR_EXPIRE_SECONDS = 5 * 60;

    /** 撤回与群公告触发会话列表刷新。 */
    public static final String CHAT_DIRTY_SIGNAL = "CHAT_DIRTY";
    public static final String CHAT_MESSAGE_SIGNAL = "CHAT_MESSAGE";

    protected final PushFanoutPublisher pushFanoutPublisher;
    private final S3StorageUtil storageUtil;
    protected final ConversationMemberMapper conversationMemberMapper;

    private boolean isSendEvent(ChatMessageMessage message) {
        return ChatEventType.SEND.getCode().equals(message.getEvent());
    }

    protected boolean isKnownEvent(ChatMessageMessage message) {
        return isSendEvent(message) || ChatEventType.WITHDRAW.getCode().equals(message.getEvent());
    }

    protected void pushMessage(Collection<Long> recipients, ChatMessageMessage message, UserSummary sender,
                               String avatarKeyOrUrl) {
        if (isSendEvent(message)) {
            NotificationForSendMessageVO payload = buildSendVO(message, sender);
            String avatarUrl = storageUtil.signGetUrl(avatarKeyOrUrl, AVATAR_EXPIRE_SECONDS);
            payload.setAvatarUrl(avatarUrl);
            String json = JsonUtil.toJson(payload);
            for (Long memberId : recipients) {
                pushFanoutPublisher.pushWs(memberId, json);
                pushFanoutPublisher.pushSseSignal(memberId, CHAT_MESSAGE_SIGNAL, payload);
            }
            return;
        }

        NotificationForWithdrawMessageVO payload = buildWithdrawVO(message, sender);
        String json = JsonUtil.toJson(payload);
        for (Long memberId : recipients) {
            pushFanoutPublisher.pushWs(memberId, json);
            pushFanoutPublisher.pushSseSignal(memberId, CHAT_DIRTY_SIGNAL);
        }
    }

    protected boolean isMuted(String conversationId, Long memberId) {
        ConversationMember doc = conversationMemberMapper.selectOne(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .eq(ConversationMember::getUserId, memberId));
        return doc != null && Boolean.TRUE.equals(doc.getMute());
    }

    /** 一次查出会话内所有开启免打扰的成员, 群聊推送用 */
    protected Set<Long> mutedMemberIds(String conversationId, Collection<Long> memberIds) {
        return conversationMemberMapper.selectList(new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .in(ConversationMember::getUserId, memberIds)
                        .eq(ConversationMember::getMute, true)).stream()
                .map(ConversationMember::getUserId)
                .collect(Collectors.toSet());
    }

    private NotificationForSendMessageVO buildSendVO(ChatMessageMessage message, UserSummary sender) {
        NotificationForSendMessageVO vo = new NotificationForSendMessageVO();
        vo.setConversationId(message.getConversationId());
        vo.setMessageId(message.getMessageId());
        vo.setSeq(message.getSeq());
        vo.setContentType(message.getContentType());
        vo.setContent(message.getContent());
        vo.setSenderId(String.valueOf(message.getSenderId()));
        vo.setSenderNickname(sender.getNickname());
        return vo;
    }

    private NotificationForWithdrawMessageVO buildWithdrawVO(ChatMessageMessage message, UserSummary sender) {
        NotificationForWithdrawMessageVO vo = new NotificationForWithdrawMessageVO();
        vo.setConversationId(message.getConversationId());
        vo.setMessageId(message.getMessageId());
        vo.setSenderId(String.valueOf(message.getSenderId()));
        vo.setSenderNickname(sender.getNickname());
        vo.setRecallType(message.getRecallType());
        return vo;
    }
}

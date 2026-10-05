package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatEventType;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForSendMessageVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForWithdrawMessageVO;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通知策略共享语义: 报文构建/免打扰查询/成员推送/会话状态落库
 */
@RequiredArgsConstructor
public abstract class AbstractConversationNotifyStrategy implements ConversationNotifyStrategy {

    protected static final long AVATAR_EXPIRE_SECONDS = 300;

    protected final PushFanoutPublisher pushFanoutPublisher;
    protected final S3StorageUtil storageUtil;
    protected final MongoTemplate mongoTemplate;

    protected boolean isSendEvent(ChatMessageMessage message) {
        return ChatEventType.SEND.getCode().equals(message.getEvent());
    }

    protected boolean isKnownEvent(ChatMessageMessage message) {
        return isSendEvent(message) || ChatEventType.WITHDRAW.getCode().equals(message.getEvent());
    }

    /** 单个成员推送: WS 报文 + SSE 门铃(未在聊天页的用户靠门铃刷新红点) */
    protected void pushMember(Long memberId, String json) {
        pushFanoutPublisher.pushWs(memberId, json);
        pushFanoutPublisher.pushSseSignal(memberId, "CHAT_DIRTY");
    }

    protected boolean isMuted(String conversationId, Long memberId) {
        Criteria c = Criteria.where("conversationId").is(conversationId).and("userId").is(memberId);
        ChatUserConversationDoc doc = mongoTemplate.findOne(Query.query(c), ChatUserConversationDoc.class);
        return doc != null && Boolean.TRUE.equals(doc.getMute());
    }

    /** 一次查出会话内所有开启免打扰的成员, 群聊推送用 */
    protected Set<Long> mutedMemberIds(String conversationId, Collection<Long> memberIds) {
        Criteria c = Criteria.where("conversationId").is(conversationId)
                .and("userId").in(memberIds)
                .and("mute").is(true);
        return mongoTemplate.find(Query.query(c), ChatUserConversationDoc.class).stream()
                .map(ChatUserConversationDoc::getUserId)
                .collect(Collectors.toSet());
    }

    protected NotificationForSendMessageVO buildSendVO(ChatMessageMessage message, UserSummary sender) {
        NotificationForSendMessageVO vo = new NotificationForSendMessageVO();
        vo.setConversationId(message.getConversationId());
        vo.setMessageId(message.getMessageId());
        vo.setContentType(message.getContentType());
        vo.setContent(message.getContent());
        vo.setSenderId(String.valueOf(message.getSenderId()));
        vo.setSenderNickname(sender.getNickname());
        return vo;
    }

    protected NotificationForWithdrawMessageVO buildWithdrawVO(ChatMessageMessage message, UserSummary sender) {
        NotificationForWithdrawMessageVO vo = new NotificationForWithdrawMessageVO();
        vo.setConversationId(message.getConversationId());
        vo.setMessageId(message.getMessageId());
        vo.setSenderId(String.valueOf(message.getSenderId()));
        vo.setSenderNickname(sender.getNickname());
        vo.setRecallType(message.getRecallType());
        return vo;
    }

    /** 批量落"最近同步时间", SEND 时顺带累加未读 */
    protected void touchMembers(String conversationId, Collection<Long> memberIds, boolean unread) {
        Update up = new Update().set("lastPullAt", LocalDateTime.now());
        if (unread) {
            up.inc("unreadCount");
        }
        mongoTemplate.updateMulti(Query.query(Criteria.where("conversationId").is(conversationId)
                .and("userId").in(memberIds)), up, ChatUserConversationDoc.class);
    }
}

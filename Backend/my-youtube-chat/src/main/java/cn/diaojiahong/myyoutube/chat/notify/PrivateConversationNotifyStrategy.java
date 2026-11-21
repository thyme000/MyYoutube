package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.vo.NotificationForSendMessageVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.NotificationForWithdrawMessageVO;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import lombok.extern.slf4j.Slf4j;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 私聊通知: 只推给接收人, 头像用发送人头像。免打扰则不推。
 */
@Slf4j
@Component
public class PrivateConversationNotifyStrategy extends AbstractConversationNotifyStrategy {

    protected PrivateConversationNotifyStrategy(PushFanoutPublisher pushFanoutPublisher,
                                                S3StorageUtil storageUtil,
                                                ConversationMemberMapper conversationMemberMapper) {
        super(pushFanoutPublisher, storageUtil, conversationMemberMapper);
    }

    @Override
    public ConversationTypeEnum type() {
        return ConversationTypeEnum.SINGLE;
    }

    @Override
    public void notify(Conversation conversation, ChatMessageMessage message, UserSummary sender) {
        List<Long> memberIds = new ArrayList<>(activeMemberIds(conversation.getId()));
        memberIds.remove(message.getSenderId());
        if (memberIds.isEmpty()) {
            log.warn("会话 {} 没有接收人", conversation.getId());
            return;
        }
        Long receiverId = memberIds.get(0);
        if (!isKnownEvent(message) || isMuted(conversation.getId(), receiverId)) {
            return;
        }
        String avatarUrl = storageUtil.signGetUrl(sender.getAvatarUrl(), AVATAR_EXPIRE_SECONDS);
        String json;
        if (isSendEvent(message)) {
            NotificationForSendMessageVO vo = buildSendVO(message, sender);
            vo.setAvatarUrl(avatarUrl);
            json = JsonUtil.toJson(vo);
        } else {
            NotificationForWithdrawMessageVO vo = buildWithdrawVO(message, sender);
            vo.setAvatarUrl(avatarUrl);
            json = JsonUtil.toJson(vo);
        }
        pushMember(receiverId, json);
    }
}

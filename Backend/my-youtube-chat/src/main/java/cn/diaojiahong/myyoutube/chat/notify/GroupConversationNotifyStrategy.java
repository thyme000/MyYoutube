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
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 群聊通知: 推给除发送人外仍在群里的成员。免打扰的人不推。
 */
@Component
public class GroupConversationNotifyStrategy extends AbstractConversationNotifyStrategy {

    protected GroupConversationNotifyStrategy(PushFanoutPublisher pushFanoutPublisher,
                                              S3StorageUtil storageUtil,
                                              ConversationMemberMapper conversationMemberMapper) {
        super(pushFanoutPublisher, storageUtil, conversationMemberMapper);
    }

    @Override
    public ConversationTypeEnum type() {
        return ConversationTypeEnum.GROUP;
    }

    @Override
    public void notify(Conversation conversation, ChatMessageMessage message, UserSummary sender) {
        if (!isKnownEvent(message)) {
            return;
        }
        List<Long> memberIds = new ArrayList<>(activeMemberIds(conversation.getId()));
        memberIds.remove(message.getSenderId());
        if (memberIds.isEmpty()) {
            return;
        }
        String avatarUrl = storageUtil.signGetUrl(conversation.getAvatar(), AVATAR_EXPIRE_SECONDS);
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
        Set<Long> muted = mutedMemberIds(conversation.getId(), memberIds);
        for (Long memberId : memberIds) {
            if (muted.contains(memberId)) {
                continue;
            }
            pushMember(memberId, json);
        }
    }
}

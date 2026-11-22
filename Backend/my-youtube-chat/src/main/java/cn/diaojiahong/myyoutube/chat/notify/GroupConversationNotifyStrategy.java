package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
        List<Long> memberIds = new ArrayList<>(conversationMemberMapper.selectActiveUserIds(conversation.getId()));
        memberIds.remove(message.getSenderId());
        if (memberIds.isEmpty()) {
            return;
        }
        memberIds.removeAll(mutedMemberIds(conversation.getId(), memberIds));
        if (memberIds.isEmpty()) {
            return;
        }
        pushMessage(memberIds, message, sender, conversation.getAvatar());
    }
}

package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForSendMessageVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForWithdrawMessageVO;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 群聊通知: 通知除发送人外所有成员(免打扰跳过), 头像用群头像
 */
@Component
public class GroupConversationNotifyStrategy extends AbstractConversationNotifyStrategy {

    protected GroupConversationNotifyStrategy(PushFanoutPublisher pushFanoutPublisher,
                                              S3StorageUtil storageUtil,
                                              MongoTemplate mongoTemplate) {
        super(pushFanoutPublisher, storageUtil, mongoTemplate);
    }

    @Override
    public ConversationTypeEnum type() {
        return ConversationTypeEnum.GROUP;
    }

    @Override
    public void notify(ChatConversationDoc conversation, ChatMessageMessage message, UserSummary sender) {
        List<Long> memberIds = new ArrayList<>(conversation.getMemberIds());
        memberIds.remove(message.getSenderId());
        boolean send = isSendEvent(message);
        if (isKnownEvent(message)) {
            String avatarUrl = storageUtil.signGetUrl(conversation.getAvatar(), AVATAR_EXPIRE_SECONDS);
            String json;
            if (send) {
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
                    // 开启免打扰 -> 不通知
                    continue;
                }
                pushMember(memberId, json);
            }
        }
        touchMembers(conversation.getId(), memberIds, send);
    }
}

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 私聊通知: 只通知接收人, 头像用发送人头像
 */
@Slf4j
@Component
public class PrivateConversationNotifyStrategy extends AbstractConversationNotifyStrategy {

    protected PrivateConversationNotifyStrategy(PushFanoutPublisher pushFanoutPublisher,
                                                S3StorageUtil storageUtil,
                                                MongoTemplate mongoTemplate) {
        super(pushFanoutPublisher, storageUtil, mongoTemplate);
    }

    @Override
    public ConversationTypeEnum type() {
        return ConversationTypeEnum.SINGLE;
    }

    @Override
    public void notify(ChatConversationDoc conversation, ChatMessageMessage message, UserSummary sender) {
        List<Long> memberIds = new ArrayList<>(conversation.getMemberIds());
        memberIds.remove(message.getSenderId());
        if (memberIds.isEmpty()) {
            log.warn("会话 {} 没有接收人", conversation.getId());
            return;
        }
        Long receiverId = memberIds.get(0);
        if (isMuted(conversation.getId(), receiverId)) {
            // 开启免打扰 -> 不通知
            return;
        }

        boolean send = isSendEvent(message);
        if (isKnownEvent(message)) {
            String avatarUrl = storageUtil.signGetUrl(sender.getAvatarUrl(), AVATAR_EXPIRE_SECONDS);
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
            pushMember(receiverId, json);
        }
        touchMembers(conversation.getId(), List.of(receiverId), send);
    }
}

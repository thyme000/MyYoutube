package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatConversationService;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import cn.diaojiahong.myyoutube.mq.message.GroupDeletedMessage;
import cn.diaojiahong.myyoutube.notification.domain.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.enums.RefTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import com.google.gson.GsonBuilder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_CHAT_GROUP_DELETED,
        consumerGroup = "chat_group_deleted_group",
        maxReconsumeTimes = 3)
public class ChatConversationListener implements RocketMQListener<GroupDeletedMessage> {

    private final ChatConversationService chatConversationService;
    private final IUserNotificationService userNotificationService;
    private final NotifyWebSocketHandler notifyWebSocketHandler;
    private final S3StorageUtil storageUtil;

    @Override
    public void onMessage(GroupDeletedMessage message) {
        log.info("收到通知群成员删除消息: {}", message);
        String conversationId = message.getConversationId();
        List<Long> memberIds = message.getUserIds();
        if (memberIds.isEmpty() || conversationId == null) {
            return;
        }
        Optional<ChatConversationDoc> opt = chatConversationService.getById(conversationId);
        if (opt.isEmpty()) {
            log.warn("群聊不存在: {}", conversationId);
            return;
        }
        ChatConversationDoc conversation = opt.get();
        List<UserNotification> userNotifications = new ArrayList<>();
        for (Long memberId : memberIds) {
            UserNotification userNotification = new UserNotification();
            userNotification.setReceiverId(memberId);
            userNotification.setType(NotificationTypeEnum.CHAT_NOTICE.getCode());
            userNotification.setContent("群:" + conversation.getName() + "已解散");
            userNotification.setRefType(RefTypeEnum.CHAT.getCode());
            userNotification.setExtraJson(conversationId);
            userNotifications.add(userNotification);
            // 通知用户
            ChatNotificationForJson json = new ChatNotificationForJson();
            json.setType(NotificationTypeEnum.CHAT_NOTICE.getCode());
            json.setContent("群:" + conversation.getName() + "已解散");
            json.setConversationAvatarUrl(storageUtil.signGetUrl(conversation.getAvatar(), 300));
            json.setConversationName(conversation.getName());
            json.setConversationId(conversationId);
            notifyWebSocketHandler.sendToUser(memberId, new GsonBuilder().create().toJson(json));
        }
        userNotificationService.saveBatch(userNotifications);
    }

    @Data
    private class ChatNotificationForJson {
        // 通知类型
        private String type;
        // 通知内容
        private String content;
        // 会话头像
        private String conversationAvatarUrl;
        // 会话名称
        private String conversationName;
        // 会话ID
        private String conversationId;
    }
}

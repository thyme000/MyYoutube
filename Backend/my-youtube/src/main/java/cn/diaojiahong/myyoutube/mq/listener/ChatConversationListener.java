package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatConversationService;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import cn.diaojiahong.myyoutube.notification.domain.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.enums.RefTypeEnum;
import cn.diaojiahong.myyoutube.notification.domain.po.UserNotification;
import cn.diaojiahong.myyoutube.notification.service.IUserNotificationService;
import com.google.gson.GsonBuilder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatConversationListener {

    private final ChatConversationService chatConversationService;
    private final IUserNotificationService userNotificationService;
    private final NotifyWebSocketHandler notifyWebSocketHandler;
    private final AliOssUtil aliOssUtil;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = NOTIFY_GROUP_MEMBER_DELETED_QUEUE, durable = "true"),
            exchange = @Exchange(name = NOTIFY_GROUP_MEMBER_DELETED_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = NOTIFY_GROUP_MEMBER_DELETED_ROUTING_KEY
    ))
    public void handleNotifyGroupMemberDeleted(String conversationId) {
        log.info("收到通知群成员删除消息: {}", conversationId);
        Optional<ChatConversationDoc> opt = chatConversationService.getById(conversationId);
        if (opt.isEmpty()) {
            log.warn("群聊不存在: {}", conversationId);
            return;
        }
        ChatConversationDoc conversation = opt.get();
        List<Long> memberIds = conversation.getMemberIds();
        memberIds.remove(Long.valueOf(UserContext.getUserId()));
        List<UserNotification> userNotifications = new ArrayList<>();
        for (Long memberId : memberIds) {
            UserNotification userNotification = new UserNotification();
            userNotification.setReceiverId(memberId);
            userNotification.setType(NotificationTypeEnum.CHAT_NOTICE.getCode());
            userNotification.setContent("群:" + conversation.getName() + "已解散");
            userNotification.setRefType(RefTypeEnum.CHAT.getCode());
            userNotification.setRefId(Long.valueOf(conversationId));
            userNotifications.add(userNotification);
            // 通知用户
            ChatNotificationForJson json = new ChatNotificationForJson();
            json.setType(NotificationTypeEnum.CHAT_NOTICE.getCode());
            json.setContent("群:" + conversation.getName() + "已解散");
            json.setConversationAvatarUrl(aliOssUtil.signGetUrl(conversation.getAvatar(), 300));
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

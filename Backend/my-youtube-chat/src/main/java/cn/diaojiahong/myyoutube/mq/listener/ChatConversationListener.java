package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.service.impl.ConversationService;
import cn.diaojiahong.myyoutube.mq.message.GroupDeletedMessage;
import cn.diaojiahong.myyoutube.video.api.NotificationPort;
import cn.diaojiahong.myyoutube.video.api.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.RefTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = GroupDeletedMessage.TOPIC,
        consumerGroup = "chat_group_deleted_group",
        maxReconsumeTimes = 3)
public class ChatConversationListener implements RocketMQListener<GroupDeletedMessage> {

    private final ConversationService chatConversationService;
    private final NotificationPort notificationPort;

    @Override
    public void onMessage(GroupDeletedMessage message) {
        String conversationId = message.getConversationId();
        List<Long> memberIds = message.getUserIds();
        if (memberIds.isEmpty() || conversationId == null) {
            log.debug("群解散通知跳过: 会话或成员为空 conversationId={}", conversationId);
            return;
        }
        Optional<Conversation> opt = chatConversationService.getById(conversationId);
        if (opt.isEmpty()) {
            log.warn("群聊不存在, 解散通知已丢弃 conversationId={} memberCount={}", conversationId, memberIds.size());
            return;
        }
        Conversation conversation = opt.get();
        for (Long memberId : memberIds) {
            notificationPort.createNotification(new NotificationPort.NotificationCreate(
                    memberId, null, NotificationTypeEnum.CHAT_NOTICE.getCode(),
                    "群:" + conversation.getName() + "已解散",
                    RefTypeEnum.CHAT.getCode(), null, conversationId));
            // 门铃由 video 端 NotificationPublisher 统一发, 这里不再自补
        }
        log.info("群解散通知已发放 conversationId={} notified={}", conversationId, memberIds.size());
    }
}

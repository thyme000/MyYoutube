package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.notify.ConversationNotifyRegistry;
import cn.diaojiahong.myyoutube.chat.notify.ConversationNotifyStrategy;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatConversationService;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_CHAT_MESSAGE,
        consumerGroup = "chat_message_group",
        maxReconsumeTimes = 3)
public class ChatMessageListener implements RocketMQListener<ChatMessageMessage> {

    private final UserPort userPort;
    private final ChatConversationService chatConversationService;
    private final ConversationNotifyRegistry notifyRegistry;

    @Override
    public void onMessage(ChatMessageMessage chatMessageMessage) {
        log.info("收到消息: {}", chatMessageMessage);
        Optional<ChatConversationDoc> opt =
                chatConversationService.getById(chatMessageMessage.getConversationId());
        if (opt.isEmpty()) {
            log.warn("会话不存在: {}", chatMessageMessage.getConversationId());
            return;
        }
        ChatConversationDoc conversation = opt.get();
        UserSummary user = userPort.getUser(chatMessageMessage.getSenderId());
        ConversationNotifyStrategy strategy = notifyRegistry.get(conversation.getType());
        if (strategy == null) {
            log.warn("会话 {} 通知类型未注册: {}", conversation.getId(), conversation.getType());
            return;
        }
        strategy.notify(conversation, chatMessageMessage, user);
    }
}

package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.notify.ConversationNotifyRegistry;
import cn.diaojiahong.myyoutube.chat.notify.ConversationNotifyStrategy;
import cn.diaojiahong.myyoutube.chat.service.impl.ConversationService;
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
        topic = ChatMessageMessage.TOPIC,
        consumerGroup = "chat_message_group",
        maxReconsumeTimes = 3)
public class ChatMessageListener implements RocketMQListener<ChatMessageMessage> {

    private final UserPort userPort;
    private final ConversationService chatConversationService;
    private final ConversationNotifyRegistry notifyRegistry;

    @Override
    public void onMessage(ChatMessageMessage chatMessageMessage) {
        // 逐条消费, 只留结构化字段; content 是消息正文, 不进日志
        log.debug("Chat message received conversationId={} messageId={} senderId={} contentType={} event={}",
                chatMessageMessage.getConversationId(), chatMessageMessage.getMessageId(),
                chatMessageMessage.getSenderId(), chatMessageMessage.getContentType(),
                chatMessageMessage.getEvent());
        Optional<Conversation> opt =
                chatConversationService.getById(chatMessageMessage.getConversationId());
        if (opt.isEmpty()) {
            log.warn("会话不存在, 通知已丢弃 conversationId={} messageId={}",
                    chatMessageMessage.getConversationId(), chatMessageMessage.getMessageId());
            return;
        }
        Conversation conversation = opt.get();
        UserSummary user = userPort.getUser(chatMessageMessage.getSenderId());
        if (user == null) {
            // 发送人已注销: 通知报文拼不出昵称, 丢弃即可, 抛异常只会白白触发重试
            log.warn("发送人不存在, 通知已丢弃 conversationId={} senderId={} messageId={}",
                    chatMessageMessage.getConversationId(),
                    chatMessageMessage.getSenderId(),
                    chatMessageMessage.getMessageId());
            return;
        }
        ConversationNotifyStrategy strategy = notifyRegistry.get(conversation.getType());
        if (strategy == null) {
            log.warn("会话通知类型未注册, 通知已丢弃 conversationId={} type={} messageId={}",
                    conversation.getId(), conversation.getType(), chatMessageMessage.getMessageId());
            return;
        }
        strategy.notify(conversation, chatMessageMessage, user);
    }
}

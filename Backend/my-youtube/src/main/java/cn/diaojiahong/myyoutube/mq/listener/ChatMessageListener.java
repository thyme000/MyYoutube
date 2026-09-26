package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatEventType;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForSendMessageVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForWithdrawMessageVO;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatConversationService;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import com.google.gson.GsonBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_CHAT_MESSAGE,
        consumerGroup = "chat_message_group",
        maxReconsumeTimes = 3)
public class ChatMessageListener implements RocketMQListener<ChatMessageMessage> {

    private final IUserService userService;
    private final NotifyWebSocketHandler notifyWebSocketHandler;
    private final ChatConversationService chatConversationService;
    private final S3StorageUtil storageUtil;
    private final MongoTemplate mongoTemplate;

    @Override
    public void onMessage(ChatMessageMessage chatMessageMessage) {
        log.info("收到消息: {}", chatMessageMessage);
        // 判断是否是群聊
        Optional<ChatConversationDoc> opt =
                chatConversationService.getById(chatMessageMessage.getConversationId());
        if (opt.isEmpty()) {
            log.warn("会话不存在: {}", chatMessageMessage.getConversationId());
            return;
        }
        ChatConversationDoc conversation = opt.get();
        List<Long> memberIds = conversation.getMemberIds();
        User user = userService.getById(chatMessageMessage.getSenderId());
        Update up = new Update().set("lastPullAt", LocalDateTime.now());
        if (conversation.getType().equals(ConversationTypeEnum.GROUP)) {
            // 发送消息事件
            // 群聊 -> 通知除发送人之外的所有成员(不包括开启免打扰的用户)
            memberIds.remove(chatMessageMessage.getSenderId());
            if (chatMessageMessage.getEvent().equals(ChatEventType.SEND.getCode())) {
                up.inc("unreadCount");
                // 通知所有成员
                NotificationForSendMessageVO json = getNotificationForSendVO(chatMessageMessage, user, conversation);
                json.setAvatarUrl(storageUtil.signGetUrl(conversation.getAvatar(), 300));
                String jsonVO = new GsonBuilder().create().toJson(json);
                // 通知所有成员(不包括开启免打扰的用户)
                for (Long memberId : memberIds) {
                    Criteria c = Criteria.where("conversationId").is(conversation.getId()).and("userId").is(memberId);
                    ChatUserConversationDoc chatUserConversationDoc = mongoTemplate.findOne(Query.query(c), ChatUserConversationDoc.class);
                    if (chatUserConversationDoc != null && chatUserConversationDoc.getMute()) {
                        // 开启免打扰 -> 不通知
                        continue;
                    }
                    notifyWebSocketHandler.sendToUser(memberId, jsonVO);
                }
            } else if (chatMessageMessage.getEvent().equals(ChatEventType.WITHDRAW.getCode())){
                // 撤销消息事件
                // 通知所有成员
                NotificationForWithdrawMessageVO json = getNotificationForWithdrawVO(chatMessageMessage, user, conversation);
                json.setAvatarUrl(storageUtil.signGetUrl(conversation.getAvatar(), 300));
                String jsonVO = new GsonBuilder().create().toJson(json);
                // 通知所有成员(不包括开启免打扰的用户)
                for (Long memberId : memberIds) {
                    Criteria c = Criteria.where("conversationId").is(conversation.getId()).and("userId").is(memberId);
                    ChatUserConversationDoc chatUserConversationDoc = mongoTemplate.findOne(Query.query(c), ChatUserConversationDoc.class);
                    if (chatUserConversationDoc != null && chatUserConversationDoc.getMute()) {
                        // 开启免打扰 -> 不通知
                        continue;
                    }
                    notifyWebSocketHandler.sendToUser(memberId, jsonVO);
                }
            }
        } else {
            // 私聊 -> 通知接收人
            if (memberIds.isEmpty()) {
                log.warn("会话 {} 没有成员", conversation.getId());
                return;
            }
            memberIds.remove(chatMessageMessage.getSenderId());
            Long receiverId = memberIds.get(0);
            // 判断用户是否对此会话开启了免打扰
            Criteria c = Criteria.where("conversationId").is(conversation.getId()).and("userId").is(receiverId);
            ChatUserConversationDoc chatUserConversationDoc = mongoTemplate.findOne(Query.query(c), ChatUserConversationDoc.class);
            if (chatUserConversationDoc != null && chatUserConversationDoc.getMute()) {
                // 开启免打扰 -> 不通知
                return;
            }
            if (chatMessageMessage.getEvent().equals(ChatEventType.SEND.getCode())) {
                // 发送消息事件
                up.inc("unreadCount");
                // 获取发送人头像url
                NotificationForSendMessageVO json = getNotificationForSendVO(chatMessageMessage, user, conversation);
                json.setAvatarUrl(storageUtil.signGetUrl(user.getAvatarUrl(), 300));
                notifyWebSocketHandler.sendToUser(receiverId, new GsonBuilder().create().toJson(json));
            } else if (chatMessageMessage.getEvent().equals(ChatEventType.WITHDRAW.getCode())){
                // 撤回消息事件
                NotificationForWithdrawMessageVO json = getNotificationForWithdrawVO(chatMessageMessage, user, conversation);
                json.setAvatarUrl(storageUtil.signGetUrl(user.getAvatarUrl(), 300));
                notifyWebSocketHandler.sendToUser(receiverId, new GsonBuilder().create().toJson(json));
            }
        }
        // 更新用户会话状态最后同步时间
        Criteria c = Criteria.where("conversationId").is(conversation.getId()).and("userId").in(memberIds);
        mongoTemplate.updateMulti(Query.query(c), up, ChatUserConversationDoc.class);
    }

    private NotificationForSendMessageVO getNotificationForSendVO(ChatMessageMessage message, User user, ChatConversationDoc conversation) {
        NotificationForSendMessageVO json = new NotificationForSendMessageVO();
        json.setConversationId(message.getConversationId());
        json.setMessageId(message.getMessageId());
        json.setContentType(message.getContentType());
        json.setContent(message.getContent());
        json.setSenderId(String.valueOf(message.getSenderId()));
        json.setSenderNickname(user.getNickname());
        return json;
    }

    private NotificationForWithdrawMessageVO getNotificationForWithdrawVO(ChatMessageMessage message, User user, ChatConversationDoc conversation) {
        NotificationForWithdrawMessageVO json = new NotificationForWithdrawMessageVO();
        json.setConversationId(message.getConversationId());
        json.setMessageId(message.getMessageId());
        json.setSenderId(String.valueOf(message.getSenderId()));
        json.setSenderNickname(user.getNickname());
        json.setRecallType(message.getRecallType());
        return json;
    }
}

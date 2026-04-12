package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatEventType;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForReadMessageVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForSendMessageVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.NotificationForWithdrawMessageVO;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatConversationService;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.mq.message.ReadMessageMessage;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import com.google.gson.GsonBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.jmx.export.naming.MetadataNamingStrategy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageListener {

    private final IUserService userService;
    private final NotifyWebSocketHandler notifyWebSocketHandler;
    private final ChatConversationService chatConversationService;
    private final AliOssUtil aliOssUtil;
    private final MongoTemplate mongoTemplate;
    private final MetadataNamingStrategy metadataNamingStrategy;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MESSAGE_QUEUE, durable = "true"),
            exchange = @Exchange(name = MESSAGE_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MESSAGE_ROUTING_KEY
    ))
    public void sendOrWithdrawMessage(ChatMessageMessage chatMessageMessage) {
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
            up.inc("unreadCount");
            // 群聊 -> 通知除发送人之外的所有成员(不包括开启免打扰的用户)
            memberIds.remove(chatMessageMessage.getSenderId());
            if (chatMessageMessage.getEvent().equals(ChatEventType.SEND.getCode())) {
                // 通知所有成员
                NotificationForSendMessageVO json = getNotificationForSendVO(chatMessageMessage, user, conversation);
                json.setAvatarUrl(aliOssUtil.signGetUrl(conversation.getAvatar(), 300));
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
                json.setAvatarUrl(aliOssUtil.signGetUrl(conversation.getAvatar(), 300));
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
                json.setAvatarUrl(aliOssUtil.signGetUrl(user.getAvatarUrl(), 300));
                notifyWebSocketHandler.sendToUser(receiverId, new GsonBuilder().create().toJson(json));
            } else if (chatMessageMessage.getEvent().equals(ChatEventType.WITHDRAW.getCode())){
                // 撤回消息事件
                NotificationForWithdrawMessageVO json = getNotificationForWithdrawVO(chatMessageMessage, user, conversation);
                json.setAvatarUrl(aliOssUtil.signGetUrl(user.getAvatarUrl(), 300));
                notifyWebSocketHandler.sendToUser(receiverId, new GsonBuilder().create().toJson(json));
            }
        }
        // 更新用户会话状态最后同步时间
        Criteria c = Criteria.where("conversationId").is(conversation.getId()).and("userId").in(memberIds);
        mongoTemplate.updateMulti(Query.query(c), up, ChatUserConversationDoc.class);
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = READ_MESSAGE_QUEUE, durable = "true"),
            exchange = @Exchange(name = READ_MESSAGE_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = READ_MESSAGE_ROUTING_KEY
    ))
    public void readMessage(ReadMessageMessage readMessageMessage) {
        // 通知对方,自己最后已读的序列
        NotificationForReadMessageVO json = new NotificationForReadMessageVO();
        json.setConversationId(readMessageMessage.getConversationId());
        json.setLastReadSequence(readMessageMessage.getLastReadSeq());
        notifyWebSocketHandler.sendToUser(Long.parseLong(readMessageMessage.getReceiverId()), new GsonBuilder().create().toJson(json));
    }

    private NotificationForWithdrawMessageVO getNotificationForWithdrawVO(
            ChatMessageMessage chatMessageMessage, User user, ChatConversationDoc conversation) {
        NotificationForWithdrawMessageVO json = new NotificationForWithdrawMessageVO();
        json.setMessageId(chatMessageMessage.getMessageId());
        json.setSenderNickname(user.getNickname());
        json.setConversationId(conversation.getId());
        json.setSenderId(chatMessageMessage.getSenderId().toString());
        return json;
    }

    private NotificationForSendMessageVO getNotificationForSendVO(
            ChatMessageMessage chatMessageMessage, User user, ChatConversationDoc conversation) {
        NotificationForSendMessageVO json = new NotificationForSendMessageVO();
        json.setMessageId(chatMessageMessage.getMessageId());
        json.setSenderNickname(user.getNickname());
        json.setConversationId(conversation.getId());
        json.setSenderId(chatMessageMessage.getSenderId().toString());
        if (chatMessageMessage.getContentType().equals(ContentTypeEnum.TEXT)) {
            // 只有文本消息通知时才需要内容字段
            json.setContent(chatMessageMessage.getContent());
        }
        json.setContentType(chatMessageMessage.getContentType());
        return json;
    }
}

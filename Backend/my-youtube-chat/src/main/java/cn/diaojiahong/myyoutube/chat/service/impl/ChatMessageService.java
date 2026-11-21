package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.chat.chain.message.MessageSendChain;
import cn.diaojiahong.myyoutube.chat.chain.message.MessageSendContext;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.MessageMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.domain.MessagePreview;
import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageWithdrawDTO;
import cn.diaojiahong.myyoutube.chat.domain.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.RecallTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.*;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategy;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.mq.message.ReadMessageMessage;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper conversationMemberMapper;
    private final ConversationService chatConversationService;
    private final PrivateConversationService privateConversationService;
    private final RocketMQTemplate rocketMQTemplate;
    private final UserPort userPort;
    private final ContentInternalPort contentInternalPort;
    private final ChatContentTypeStrategyRegistry contentTypeStrategies;
    private final SyncChainStepRegistry chainRegistry;
    private final TransactionTemplate transactionTemplate;
    private final UserBlockPort blocks;
    private final FollowPort followPort;

    public Optional<Message> findByIdempotent(Long senderId, String clientMsgId) {
        return Optional.ofNullable(messageMapper.selectById(MessageSendContext.messageId(senderId, clientMsgId)));
    }

    @ObservedBusiness(BusinessOperation.SEND_MESSAGE)
    public ChatMessageAckDTO send(ChatMessageDTO dto) {
        String userId = getUserId();
        String conversationId = dto.getConversationId();
        Long peerId = dto.getPeerUserId();
        boolean hasConversation = conversationId != null && !conversationId.isBlank();
        boolean hasPeer = peerId != null;
        if (hasConversation == hasPeer) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "会话 ID 与对方用户 ID 必须二选一");
        }
        if (hasConversation) {
            return sendToConversation(dto, userId, conversationId);
        }
        requirePrivatePeer(Long.valueOf(userId), peerId);
        return sendToPeer(dto, userId, peerId);
    }

    private void requirePrivatePeer(Long userId, Long peerId) {
        if (peerId <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "对方用户 ID 无效");
        }
        if (peerId.equals(userId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不能与自己发起私聊");
        }
    }

    private ChatMessageAckDTO sendToPeer(ChatMessageDTO dto, String userId, Long peerId) {
        Long senderId = Long.valueOf(userId);
        ChatContentTypeStrategy strategy = contentTypeStrategies.require(dto.getContentType());
        strategy.validate(dto.getContent());

        Conversation existing = privateConversationService.findPrivateChat(senderId, peerId);
        if (existing != null) {
            // A 已打开、发送人还没打开过(如 B 直接发首条): 补发送人成员行(不可见档)后走常规发送,
            // 发送失败时残留的行不进列表, 发送成功后 applyTail 把它点亮
            privateConversationService.ensurePrivateMember(existing.getId(), senderId, false, LocalDateTime.now());
            return sendToConversation(dto, userId, existing.getId());
        }

        requirePrivateChatAllowed(senderId, peerId);
        privateConversationService.validatePrivatePeer(peerId);
        boolean mutual = followPort.interrelated(new FollowPort.InterrelatedQuery(senderId, List.of(peerId)));
        ConversationStatus status = ConversationStatus.afterFirstPrivateMessage(mutual);
        Message message = prepareMessage(dto, senderId, strategy);
        String preview = MessagePreview.clip(strategy.previewLabel(message.getContent()));

        try {
            transactionTemplate.executeWithoutResult(tx -> {
                Conversation created = privateConversationService.insertPrivateConversation(
                        senderId, peerId, status, message.getCreatedAt());
                message.setConversationId(created.getId());
                persistMessage(message, preview, null);
            });
        } catch (DuplicateKeyException duplicate) {
            return recoverDuplicateOnFirstSend(dto, userId, senderId, peerId, duplicate);
        }
        return notifySent(message);
    }

    /**
     * 首发撞唯一键的两种来源要分开处理:
     * ① uk_private_pair: 对方(或自己的另一请求)并发首发建了会话。MySQL 唯一索引的插入
     *    冲突要等对方事务终局才返回, 所以此时回查必能看到那个会话, 直接走常规发送校验。
     * ② message 主键: 同一 clientMsgId 已用在别的会话, 属于消息幂等冲突。
     */
    private ChatMessageAckDTO recoverDuplicateOnFirstSend(
            ChatMessageDTO dto, String userId, Long senderId, Long peerId, DuplicateKeyException duplicate) {
        Conversation found = privateConversationService.findPrivateChat(senderId, peerId);
        if (found != null) {
            privateConversationService.ensurePrivateMember(found.getId(), senderId, false, LocalDateTime.now());
            return sendToConversation(dto, userId, found.getId());
        }
        if (findByIdempotent(senderId, dto.getClientMsgId()).isPresent()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "会话id不一致");
        }
        throw duplicate;
    }

    private void requirePrivateChatAllowed(Long userId, Long peerId) {
        if (blocks.blocksPrivateChat(userId, peerId)) {
            throw new BizException(ErrorCode.FORBIDDEN, Conversation.PRIVATE_MESSAGE_UNAVAILABLE);
        }
    }

    private ChatMessageAckDTO sendToConversation(ChatMessageDTO dto, String userId, String conversationId) {
        // 类型校验/会话与成员守卫/禁言/幂等/互关频控全部在链上, 步骤见 chat.chain.message
        MessageSendContext ctx = MessageSendContext.of(dto, userId, conversationId);
        chainRegistry.execute(MessageSendChain.GROUP, ctx, MessageSendContext::hasAck);
        if (ctx.hasAck()) {
            return ctx.getAck();
        }

        Conversation conversation = ctx.getConversation();
        Long senderId = Long.valueOf(userId);
        boolean admissionRequired = conversation.requiresPrivateAdmission();
        ChatContentTypeStrategy strategy = contentTypeStrategies.require(dto.getContentType());
        Message message = prepareMessage(dto, senderId, strategy);
        message.setConversationId(conversationId);
        String preview = MessagePreview.clip(strategy.previewLabel(message.getContent()));
        String senderNickname = groupSenderNickname(conversation.getType() == ConversationTypeEnum.GROUP, senderId);

        Message existing;
        try {
            existing = transactionTemplate.execute(tx -> {
                // 先锁住待发首条或等待回复的私聊，再读幂等记录，避免并发重试被误判为第二条问候。
                Conversation locked = admissionRequired ? lockActiveConversation(conversationId) : null;
                Message old = findByIdempotent(senderId, dto.getClientMsgId()).orElse(null);
                if (old != null) {
                    return old;
                }
                if (locked != null) {
                    privateConversationService.admitPrivateConversation(locked, senderId, ctx.isInterrelated());
                }
                persistMessage(message, preview, senderNickname);
                return null;
            });
        } catch (DuplicateKeyException duplicate) {
            existing = findByIdempotent(senderId, dto.getClientMsgId()).orElse(null);
            if (existing == null) {
                throw duplicate;
            }
        }
        if (existing != null) {
            if (!conversationId.equals(existing.getConversationId())) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "会话id不一致");
            }
            return getAck(existing, existing.getStatus());
        }
        return notifySent(message);
    }

    private Message prepareMessage(ChatMessageDTO dto, Long senderId, ChatContentTypeStrategy strategy) {
        Message message = new Message();
        message.setSenderId(senderId);
        message.setId(MessageSendContext.messageId(senderId, dto.getClientMsgId()));
        message.setClientMsgId(dto.getClientMsgId());
        message.setContentType(dto.getContentType());
        message.setCreatedAt(LocalDateTime.now());
        message.setStatus(MessageStatusEnum.SENT);
        // 转发快照可能需要远程调用，在事务外完成。
        message.setContent(strategy.snapshot(dto.getContent()));
        return message;
    }

    private void persistMessage(Message message, String preview, String senderNickname) {
        String conversationId = message.getConversationId();
        LocalDateTime now = message.getCreatedAt();
        int updated = conversationMapper.advanceOnMessage(conversationId, now);
        if (updated != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "会话不存在或已解散");
        }
        // UPDATE 拿到的行锁串行化了并发递增，本事务内回读看到的就是自己刚加的值
        long seq = conversationMapper.selectLastSeq(conversationId);
        message.setSeq(seq);
        messageMapper.insert(message);
        conversationMemberMapper.applyTail(conversationId, message.getId(), now, preview, senderNickname);
        // 自己发出的消息算已读，已读水位推进到本条。
        conversationMemberMapper.advanceReadSeq(conversationId, message.getSenderId(), seq, now);
    }

    private ChatMessageAckDTO notifySent(Message message) {
        if (message.getContentType().isForward()) {
            incrementShareCountQuietly(message.getContent().getContentId(), message.getSenderId());
        }
        ChatMessageMessage notification = ChatMessageMessage.builder()
                .senderId(message.getSenderId())
                .seq(message.getSeq())
                .conversationId(message.getConversationId())
                .messageId(message.getId())
                .contentType(message.getContentType())
                .serviceTime(message.getCreatedAt())
                .event("send")
                .build();
        if (message.getContentType().isText()) {
            notification.setContent(message.getContent().getText());
        }
        try {
            rocketMQTemplate.syncSend(ChatMessageMessage.TOPIC, notification);
        } catch (RuntimeException ex) {
            log.warn("消息推送失败，接收方靠同步补齐 conversationId={} messageId={}",
                    message.getConversationId(), message.getId(), ex);
        }
        log.debug("Chat message sent conversationId={} messageId={} seq={} senderId={} contentType={}",
                message.getConversationId(), message.getId(), message.getSeq(),
                message.getSenderId(), message.getContentType());
        return getAck(message, MessageStatusEnum.SENT);
    }

    private Conversation lockActiveConversation(String conversationId) {
        Conversation locked = conversationMapper.lockById(conversationId);
        if (locked == null || locked.getStatus() == ConversationStatus.CLOSED) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "会话不存在或已解散");
        }
        return locked;
    }

    private ChatMessageAckDTO getAck(Message chatMessageDoc, MessageStatusEnum status) {
        return MessageSendContext.ackOf(chatMessageDoc, status);
    }

    private String getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }

        return userId;
    }

    @ObservedBusiness(BusinessOperation.WITHDRAW_MESSAGE)
    public ChatMessageAckDTO withdraw(ChatMessageWithdrawDTO chatMessageWithdrawDTO) {
        String userId = getUserId();
        // 发送人取消息行自身, 不采信客户端自报的 senderId
        Message chatMessageDoc = messageMapper.selectById(chatMessageWithdrawDTO.getMessageId());
        if (chatMessageDoc == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "消息不存在");
        }

        Conversation conversation = getConversation(chatMessageDoc.getConversationId());
        // 退群/解散后不再持有撤回权: 群主靠 removeConversationMember 的守卫保证留在群里
        activeMemberIds(chatMessageDoc.getConversationId());
        Long operatorId = Long.valueOf(userId);
        Long createdBy = conversation.getCreatedBy();
        boolean senderRecall = chatMessageDoc.getSenderId() != null && chatMessageDoc.getSenderId().equals(operatorId);
        boolean groupOwner = ConversationTypeEnum.GROUP.equals(conversation.getType())
                && createdBy != null
                && createdBy.equals(operatorId);
        if (!senderRecall && !groupOwner) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是发送人或群主");
        }
        LocalDateTime recallTime = LocalDateTime.now();
        // 判断是否已经撤回了,直接返回ack
        if (chatMessageDoc.getRecalled() != null && chatMessageDoc.getRecalled()) {
            return getAck(chatMessageDoc, chatMessageDoc.getStatus());
        }

        // 必须先判断「本人撤自己」：群主也是 createdBy，否则会被误判为 ADMIN。私聊没有群主撤回。
        RecallTypeEnum recallTypeForMq;
        if (senderRecall) {
            if (!groupOwner) {
                LocalDateTime createdAt = chatMessageDoc.getCreatedAt();
                // 没有发送时间就无法判定时限, 按不能撤回处理
                if (createdAt == null) {
                    throw new BizException(ErrorCode.STATE_CONFLICT, "消息缺少发送时间，无法撤回");
                }
                if (createdAt.isBefore(recallTime.minusMinutes(2))) {
                    throw new BizException(ErrorCode.STATE_CONFLICT, "消息已发送超过2分钟,不能撤回");
                }
            }
            recallTypeForMq = RecallTypeEnum.SENDER;
        } else {
            recallTypeForMq = RecallTypeEnum.ADMIN;
        }
        chatMessageDoc.setRecalled(true);
        chatMessageDoc.setRecalledAt(recallTime);
        chatMessageDoc.setStatus(MessageStatusEnum.WITHDRAWN);
        chatMessageDoc.setRecallType(recallTypeForMq);
        chatMessageDoc.setUpdatedAt(recallTime);
        Boolean applied = transactionTemplate.execute(tx -> {
            int updated = messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getId, chatMessageDoc.getId())
                    .and(notRecalled -> notRecalled.isNull(Message::getRecalled)
                            .or()
                            .eq(Message::getRecalled, false))
                    .set(Message::getRecalled, true)
                    .set(Message::getRecalledAt, recallTime)
                    .set(Message::getStatus, MessageStatusEnum.WITHDRAWN)
                    .set(Message::getRecallType, recallTypeForMq)
                    .set(Message::getUpdatedAt, recallTime));
            if (updated != 1) {
                return false;
            }
            conversationMemberMapper.markTailRecalled(
                    chatMessageDoc.getConversationId(), chatMessageDoc.getId(), recallTime);
            return true;
        });
        if (!Boolean.TRUE.equals(applied)) {
            Message current = messageMapper.selectById(chatMessageDoc.getId());
            if (current == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "消息不存在");
            }
            return getAck(current, current.getStatus());
        }
        // 推送消息到所有成员: 提交后发, MQ 失败不回滚撤回
        ChatMessageMessage ch = ChatMessageMessage.builder().senderId(chatMessageDoc.getSenderId())
                .seq(chatMessageDoc.getSeq()).conversationId(chatMessageDoc.getConversationId())
                .messageId(chatMessageDoc.getId()).contentType(chatMessageDoc.getContentType())
                .recallType(recallTypeForMq)
                .serviceTime(recallTime).event("withdraw").build();
        try {
            rocketMQTemplate.syncSend(ChatMessageMessage.TOPIC, ch);
        } catch (RuntimeException ex) {
            log.warn("撤回推送失败，接收方靠同步补齐 conversationId={} messageId={}",
                    chatMessageDoc.getConversationId(), chatMessageDoc.getId(), ex);
        }
        log.debug("Chat message withdrawn conversationId={} messageId={} seq={} operatorId={} recallType={}",
                chatMessageDoc.getConversationId(), chatMessageDoc.getId(), chatMessageDoc.getSeq(),
                userId, recallTypeForMq);
        return getAck(chatMessageDoc, MessageStatusEnum.WITHDRAWN);
    }

    public void clearHistory(String conversationId) {
        requireActiveMember(conversationId);
        Long userId = Long.valueOf(getUserId());
        transactionTemplate.executeWithoutResult(tx -> {
            Conversation conversation = lockActiveConversation(conversationId);
            conversationMemberMapper.clearHistory(conversationId, userId,
                    conversation.getLastSeq(), LocalDateTime.now());
        });
    }

    @ObservedBusiness(BusinessOperation.READ_MESSAGE)
    public void read(String conversationId, Long lastReadSeq) {
        Conversation conversation = getConversation(conversationId);
        activeMemberIds(conversationId);

        Long currentUserId = Long.parseLong(getUserId());
        long maxSeq = conversation.getLastSeq() == null ? 0L : conversation.getLastSeq();
        int advanced = conversationMemberMapper.markRead(
                conversationId, currentUserId, lastReadSeq, maxSeq, LocalDateTime.now());
        if (advanced == 0) {
            return;
        }
        // 判断是否是私聊,只有私聊才有是否已读状态,才需要推送消息
        if (!Objects.equals(conversation.getType(), ConversationTypeEnum.SINGLE)) {
            return;
        }

        ConversationMember member = conversationMemberMapper.selectOne(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .eq(ConversationMember::getUserId, currentUserId));
        // 对端按 user_a/user_b 反推, 不依赖成员表里有对方的行
        Long receiverId = conversation.peerOf(currentUserId);
        if (member == null || member.getLastReadSeq() == null || receiverId == null) {
            // 已读水位已更新成功, 这里只影响给对端的已读推送; 缺接收方记日志跳过, 不该把调用方 500
            log.warn("私聊缺少接收方, 已读推送已跳过 conversationId={} userId={}", conversationId, currentUserId);
            return;
        }
        lastReadSeq = member.getLastReadSeq();

        ReadMessageMessage vo = ReadMessageMessage.builder()
                .lastReadSeq(lastReadSeq).receiverId(receiverId.toString()).conversationId(conversationId).build();
        try {
            rocketMQTemplate.syncSend(ReadMessageMessage.TOPIC, vo);
        } catch (RuntimeException ex) {
            log.warn("已读推送失败 conversationId={} lastReadSeq={}", conversationId, lastReadSeq, ex);
        }
    }

    ConversationMember requireActiveMember(String conversationId) {
        ConversationMember member = conversationMemberMapper.selectOne(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .eq(ConversationMember::getUserId, Long.valueOf(getUserId()))
                        .eq(ConversationMember::getDeleted, false));
        if (member == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是会话成员");
        }
        return member;
    }

    private List<Long> activeMemberIds(String conversationId) {
        List<Long> memberIds = conversationMemberMapper.selectActiveUserIds(conversationId);
        if (!memberIds.contains(Long.valueOf(getUserId()))) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是会话成员");
        }
        return memberIds;
    }

    Conversation getConversation(String conversationId) {
        Optional<Conversation> opt = chatConversationService.getById(conversationId);
        if (opt.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }

        return opt.get();
    }

    private String groupSenderNickname(boolean group, Long senderId) {
        if (!group) {
            return null;
        }
        UserSummary sender = userPort.getUser(senderId);
        if (sender == null) {
            return null;
        }
        return sender.getNickname();
    }

    // 消息已提交后计数；幂等命中在 send 入口返回，不重复执行此副作用。
    private void incrementShareCountQuietly(Long contentId, Long viewerId) {
        try {
            if (!contentInternalPort.incrementShareCount(contentId, viewerId)) {
                log.warn("更新内容分享数失败, 计数已丢弃 contentId={}", contentId);
            }
        } catch (Exception e) {
            log.warn("更新内容分享数异常, 计数已丢弃 contentId={}", contentId, e);
        }
    }
}

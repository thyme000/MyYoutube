package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.mongo.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ChatMessageWithdrawDTO;
import cn.diaojiahong.myyoutube.chat.mongo.dto.MessageListPageQuery;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.RecallTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.*;
import cn.diaojiahong.myyoutube.chat.mongo.vo.MessagePage;
import cn.diaojiahong.myyoutube.chat.mongo.vo.MessagePageVO;
import cn.diaojiahong.myyoutube.chat.service.BaseMongoService;
import cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.mq.message.ReadMessageMessage;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChatMessageService extends BaseMongoService<ChatMessageDoc> {

    private static final Long OSS_EXPIRE = 3600L * 10;

    private final ChatConversationService chatConversationService;
    private final RabbitTemplate rabbitTemplate;
    private final IUserService userService;
    private final AliOssUtil aliOssUtil;
    private final StringRedisTemplate redisTemplate;
    private final IUserFollowService userFollowService;
    public ChatMessageService(MongoTemplate mongoTemplate,
                              ChatConversationService chatConversationService,
                              RabbitTemplate rabbitTemplate,
                              IUserService userService,
                              AliOssUtil aliOssUtil,
                              StringRedisTemplate redisTemplate,
                              IUserFollowService userFollowService) {
        super(mongoTemplate, ChatMessageDoc.class);
        this.chatConversationService = chatConversationService;
        this.rabbitTemplate = rabbitTemplate;
        this.userService = userService;
        this.aliOssUtil = aliOssUtil;
        this.redisTemplate = redisTemplate;
        this.userFollowService = userFollowService;
    }

    public Optional<ChatMessageDoc> findByIdempotent(Long senderId, String clientMsgId) {
        Query q = Query.query(
                Criteria.where("senderId").is(senderId)
                        .and("clientMsgId").is(clientMsgId)
        );
        return Optional.ofNullable(mongoTemplate.findOne(q, ChatMessageDoc.class));
    }

    public Page<ChatMessageDoc> pageByConversation(String conversationId, Long beforeSeq, int size) {
        size = size <= 0 ? 20 : size;
        size = Math.min(size, 100);
        Criteria c = Criteria.where("conversationId").is(conversationId);
        if (beforeSeq != null) {
            c = c.and("seq").lt(beforeSeq);
        }
        Query q = Query.query(c);
        q.with(org.springframework.data.domain.Sort.by(Sort.Direction.DESC, "seq"));
        return page(q, PageRequest.of(0, size));
    }

    public Page<ChatMessageDoc> syncPageByConversation(String conversationId, Long beforeSeq, int size) {
        size = size <= 0 ? 20 : size;
        size = Math.min(size, 100);
        Criteria c = Criteria.where("conversationId").is(conversationId);
        if (beforeSeq != null) {
            c = c.and("seq").gt(beforeSeq);
        }
        Query q = Query.query(c);
        q.with(org.springframework.data.domain.Sort.by(Sort.Direction.ASC, "seq"));
        return page(q, PageRequest.of(0, size));
    }

    @Transactional(transactionManager = "mongoTransactionManager", rollbackFor = Exception.class)
    public Result<ChatMessageAckDTO> send(ChatMessageDTO chatMessageDTO) {
        validateByType(chatMessageDTO);
        String userId = getUserId();
        String conversationId = chatMessageDTO.getConversationId();
        Criteria c = Criteria.where("conversationId").is(conversationId).and("userId").is(Long.valueOf(userId));
        Query q = Query.query(c);
        ChatUserConversationDoc userConversationDoc = mongoTemplate.findOne(q, ChatUserConversationDoc.class);
        if (userConversationDoc == null || Boolean.TRUE.equals(userConversationDoc.getDeleted())) {
            throw new BizException(BizException.CONVERSATION_ERROR, "不合法的会话状态");
        }
        // 判断是否被禁言
        if (Boolean.TRUE.equals(userConversationDoc.getBanned())) {
            return Result.fail("您已被禁言");
        }
        if (chatMessageDTO.getClientMsgId() == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "客户端幂等id不能为空");
        }
        Optional<ChatMessageDoc> existed =
                findByIdempotent(Long.valueOf(userId), chatMessageDTO.getClientMsgId());
        if (existed.isPresent()) {
            // 说明该消息在此之前已经发送过,直接返回ack
            ChatMessageDoc old = existed.get();
            if (!old.getConversationId().equals(conversationId)) {
                throw new BizException(BizException.MESSAGE_ERROR, "会话id不一致");
            }
            return Result.success(getAck(old, old.getStatus()));
        }
        // 判断是否是发送人
        if (!chatMessageDTO.getSenderId().toString().equals(userId)) {
            throw new BizException(BizException.MESSAGE_ERROR, "不是发送人");
        }
        // 判断是否是该会话的成员
        ChatConversationDoc chatConversationDoc = chatConversationService.getById(conversationId).orElse(null);
        if (chatConversationDoc == null) {
            throw new BizException(BizException.CONVERSATION_ERROR, "会话不存在");
        }
        if (!chatConversationDoc.getMemberIds().contains(chatMessageDTO.getSenderId())) {
            throw new BizException(BizException.CONVERSATION_ERROR, "不是该会话的成员");
        }
        // 判断是否开启了全员禁言 且 发送人不是创建人
        if (Boolean.TRUE.equals(chatConversationDoc.getBanned()) &&
                !chatConversationDoc.getCreatedBy().equals(Long.valueOf(userId))) {
            return Result.fail("全员禁言开启中");
        }
        boolean isInterrelated = false;
        if (chatConversationDoc.getType().equals(ConversationTypeEnum.SINGLE)) {
            chatConversationDoc.getMemberIds().remove(chatMessageDTO.getSenderId());
            isInterrelated = userFollowService.interrelated(Long.valueOf(userId), chatConversationDoc.getMemberIds());
            // 私聊,判断是否是互关关系
            if (!isInterrelated) {
                // 不是互关关系的私聊,每天只能给对方发送10条消息
                Double times = redisTemplate.opsForZSet().score(RedisConstants.USER_CONVERSATION + userId, conversationId);
                if (times != null && times > 10) {
                    return Result.fail("你们暂时还不是互关关系哦,每天只能给对方发送10条消息");
                }
            }
            chatConversationDoc.getMemberIds().add(Long.valueOf(userId));
        }
        ChatMessageDoc chatMessageDoc = BeanUtil.toBean(chatMessageDTO, ChatMessageDoc.class);
        chatMessageDoc.setCreatedAt(LocalDateTime.now());
        chatMessageDoc.setStatus(MessageStatusEnum.SENT);
        chatMessageDoc.setContent(chatMessageDTO.getContent());
        chatMessageDoc.setSeq(nextSeq(conversationId));
        mongoTemplate.save(chatMessageDoc, "chat_messages");
        if (chatConversationDoc.getType().equals(ConversationTypeEnum.SINGLE)) {
            // 私聊并且不是互关,增加消息次数
            if (!isInterrelated) {
                redisTemplate.opsForZSet().incrementScore(RedisConstants.USER_CONVERSATION + userId, conversationId, 1);
                redisTemplate.expire(RedisConstants.USER_CONVERSATION + userId,
                        RedisConstants.USER_CONVERSATION_EXPIRE, TimeUnit.DAYS);
            }
        }
        // 构建ack
        ChatMessageAckDTO chatMessageAckDTO = getAck(chatMessageDoc, MessageStatusEnum.SENT);
        // 推送消息到所有成员
        ChatMessageMessage ch = ChatMessageMessage.builder().senderId(chatMessageDoc.getSenderId())
                .seq(chatMessageDoc.getSeq()).conversationId(conversationId)
                .messageId(chatMessageDoc.getId()).contentType(chatMessageDTO.getContentType())
                .serviceTime(chatMessageDoc.getCreatedAt()).event("send").build();
        if (chatMessageDTO.getContentType().isText()) {
            ch.setContent(chatMessageDTO.getContent().getText());
        }
        // 设置会话最后消息
        chatConversationDoc.setLastMessageAt(chatMessageDoc.getCreatedAt());
        chatConversationDoc.setLastMessageId(chatMessageDoc.getId());
        chatConversationDoc.setIsLastMessageRecall(false);
        if (chatMessageDoc.getContentType().isMedia()) {
            chatConversationDoc.setLastMessagePreview("[媒体文件]");
        } else if (chatMessageDoc.getContentType().isText()) {
            chatConversationDoc.setLastMessagePreview(chatMessageDoc.getContent().getText());
        }
        if (chatConversationDoc.getType().equals(ConversationTypeEnum.GROUP)) {
            chatConversationDoc.setLastMessageSenderNickname(userService.getById(userId).getNickname());
        }
        mongoTemplate.save(chatConversationDoc, "chat_conversations");
        rabbitTemplate.convertAndSend(RabbitMqConstants.MESSAGE_EXCHANGE, RabbitMqConstants.MESSAGE_ROUTING_KEY, ch);
        return Result.success(chatMessageAckDTO);
    }

    private ChatMessageAckDTO getAck(ChatMessageDoc chatMessageDoc, MessageStatusEnum status) {
        ChatMessageAckDTO chatMessageAckDTO = new ChatMessageAckDTO();
        chatMessageAckDTO.setMessageId(chatMessageDoc.getId());
        chatMessageAckDTO.setConversationId(chatMessageDoc.getConversationId());
        chatMessageAckDTO.setSeq(chatMessageDoc.getSeq());
        chatMessageAckDTO.setServerTime(System.currentTimeMillis());
        chatMessageAckDTO.setClientMsgId(chatMessageDoc.getClientMsgId());
        chatMessageAckDTO.setStatus(status);
        return chatMessageAckDTO;
    }

    public long nextSeq(String conversationId) {
        Query q = Query.query(Criteria.where("_id").is(conversationId));
        Update up = new Update().inc("seq", 1);
        FindAndModifyOptions opts = new FindAndModifyOptions()
                .returnNew(true)   // 返回更新后的文档
                .upsert(true);     // 没有则创建 _id，seq 从 0 变 1（首次 inc）
        ChatSeqDoc doc = mongoTemplate.findAndModify(q, up, opts, ChatSeqDoc.class);
        return doc.getSeq();
    }

    private String getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            throw new BizException(BizException.USER_ERROR, "用户id不存在");
        }
        return userId;
    }

    @Transactional(transactionManager = "mongoTransactionManager", rollbackFor = Exception.class)
    public Result<ChatMessageAckDTO> withdraw(ChatMessageWithdrawDTO chatMessageWithdrawDTO) {
        String userId = getUserId();
        if (chatMessageWithdrawDTO.getClientMsgId() == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "客户端幂等id不能为空");
        }
        Optional<ChatMessageDoc> existed =
                findByIdempotent(Long.valueOf(userId), chatMessageWithdrawDTO.getClientMsgId());
        if (existed.isEmpty()) {
            throw new BizException(BizException.MESSAGE_ERROR, "消息不存在");
        }
        ChatMessageDoc chatMessageDoc = existed.get();
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(chatMessageDoc.getConversationId());
        // 判断是否是发送人撤回或群主撤回
        Long createdBy = chatConversationDoc.getCreatedBy();
        if (!chatMessageDoc.getSenderId().toString().equals(userId)
                && (createdBy == null || !createdBy.toString().equals(userId))) {
            throw new BizException(BizException.MESSAGE_ERROR, "不是发送人或群主");
        }
        LocalDateTime recallTime = LocalDateTime.now();
        // 判断是否已经撤回了,直接返回ack
        if (chatMessageDoc.getRecalled() != null && chatMessageDoc.getRecalled()) {
            return Result.success(getAck(chatMessageDoc, chatMessageDoc.getStatus()));
        }
        Criteria c = Criteria.where("conversationId").is(chatMessageDoc.getConversationId()).and("id").is(chatMessageDoc.getId());
        Update up = new Update().set("recalled", true).set("recalledAt", recallTime).set("status", MessageStatusEnum.WITHDRAWN);
        Long operatorId = Long.valueOf(userId);
        if (chatMessageDoc.getSenderId() != null && chatMessageDoc.getSenderId().equals(operatorId)) {
            // 本人撤回自己的消息：2 分钟窗口
            if (chatMessageDoc.getCreatedAt().isBefore(recallTime.minusMinutes(2))) {
                throw new BizException(BizException.MESSAGE_ERROR, "消息已发送超过2分钟,不能撤回");
            }
            up.set("recallType", RecallTypeEnum.SENDER);
        } else if (createdBy != null && createdBy.equals(operatorId)) {
            // 群主撤回他人消息：管理撤回
            up.set("recallType", RecallTypeEnum.ADMIN);
        }
        mongoTemplate.updateFirst(Query.query(c), up, ChatMessageDoc.class);
        // 构建ack
        ChatMessageAckDTO chatMessageAckDTO = getAck(chatMessageDoc, MessageStatusEnum.WITHDRAWN);
        // 设置会话最后消息撤回
        chatConversationDoc.setIsLastMessageRecall(true);
        mongoTemplate.save(chatConversationDoc, "chat_conversations");
        // 推送消息到所有成员
        ChatMessageMessage ch = ChatMessageMessage.builder().senderId(chatMessageDoc.getSenderId())
                .seq(chatMessageDoc.getSeq()).conversationId(chatMessageDoc.getConversationId())
                .messageId(chatMessageDoc.getId()).contentType(chatMessageDoc.getContentType())
                .serviceTime(recallTime).event("withdraw").build();
        rabbitTemplate.convertAndSend(RabbitMqConstants.MESSAGE_EXCHANGE, RabbitMqConstants.MESSAGE_ROUTING_KEY, ch);
        return Result.success(chatMessageAckDTO);
    }

    private void validateByType(ChatMessageDTO dto) {
        ChatContent c = dto.getContent();
        switch (dto.getContentType()) {
            case TEXT -> {
                if (c.getText() == null || c.getText().isBlank()) {
                    throw new BizException(BizException.MESSAGE_ERROR, "文本消息text不能为空");
                }
            }
            case IMAGE, FILE -> {
                if (c.getObjectKey() == null || c.getObjectKey().isBlank()
                        || c.getMimeType() == null || c.getMimeType().isBlank()
                        || c.getSize() == null || c.getSize() <= 0) {
                    throw new BizException(BizException.MESSAGE_ERROR, "媒体消息字段不完整");
                }
            }
            case SYSTEM -> {
                if (c.getSystemText() == null || c.getSystemText().isBlank()) {
                    throw new BizException(BizException.MESSAGE_ERROR, "系统消息内容不能为空");
                }
            }
            default -> {
                throw new BizException(BizException.MESSAGE_ERROR, "消息类型错误");
            }
        }
    }

    public Result<MessagePageVO> getMessageList(String conversationId, MessageListPageQuery messageListPageQuery) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        // 判断是否是会话成员
        if (!memberIds.contains(Long.valueOf(getUserId()))) {
            throw new BizException(BizException.USER_ERROR, "不是会话成员");
        }
        // 用户信息
        Map<Long, String> authorNicknames = userService.getAuthorNicknames(memberIds);
        Map<Long, String> authorAvatarUrls = userService.getAuthorAvatarUrls(memberIds);
        // 分页查询
        Page<ChatMessageDoc> page = pageByConversation(conversationId, messageListPageQuery.getSeq(), messageListPageQuery.getSize());
        List<MessagePage> result = getMessagePageVOS(page, authorNicknames, authorAvatarUrls);
        MessagePageVO vo = MessagePageVO.builder().messages(result).nextSeq(page.hasNext() ? page.getContent().get(0).getSeq() : null).hasMore(page.hasNext()).build();
        return Result.success(vo);
    }

    private List<MessagePage> getMessagePageVOS(Page<ChatMessageDoc> page, Map<Long, String> authorNicknames, Map<Long, String> authorAvatarUrls) {
        List<ChatMessageDoc> docs = page.getContent();
        return docs.stream().map(doc -> {
            MessagePage vo = new MessagePage();
            vo.setSenderId(doc.getSenderId());
            vo.setSenderNickName(authorNicknames.get(doc.getSenderId()));
            vo.setSenderAvatarUrl(aliOssUtil.signGetUrl(authorAvatarUrls.get(doc.getSenderId()), OSS_EXPIRE));
            vo.setContentType(doc.getContentType());
            if (vo.getContentType() == ContentTypeEnum.FILE || vo.getContentType() == ContentTypeEnum.IMAGE) {
                doc.getContent().setObjectKey(aliOssUtil.signGetUrl(doc.getContent().getObjectKey(), OSS_EXPIRE));
                vo.setContent(doc.getContent());
            } else {
                vo.setContent(doc.getContent());
            }
            vo.setMessageId(doc.getId());
            vo.setClientMsgId(doc.getClientMsgId());
            vo.setSeq(doc.getSeq());
            vo.setStatus(doc.getStatus());
            vo.setCreatedAt(doc.getCreatedAt());
            return vo;
        }).toList();
    }

    public Result<MessagePageVO> syncMessageList(String conversationId, @Valid MessageListPageQuery messageListPageQuery) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        // 判断是否是会话成员
        if (!memberIds.contains(Long.valueOf(getUserId()))) {
            throw new BizException(BizException.USER_ERROR, "不是会话成员");
        }
        // 用户信息
        Map<Long, String> authorNicknames = userService.getAuthorNicknames(memberIds);
        Map<Long, String> authorAvatarUrls = userService.getAuthorAvatarUrls(memberIds);
        // 分页查询
        Page<ChatMessageDoc> page = syncPageByConversation(conversationId, messageListPageQuery.getSeq(), messageListPageQuery.getSize());
        List<MessagePage> result = getMessagePageVOS(page, authorNicknames, authorAvatarUrls);
        MessagePageVO vo = MessagePageVO.builder().messages(result).nextSeq(page.hasNext() ? page.getContent().get(0).getSeq() : null).hasMore(page.hasNext()).build();
        return Result.success(vo);
    }

    @Transactional(transactionManager = "mongoTransactionManager", rollbackFor = Exception.class)
    public Result<Void> read(String conversationId, Long lastReadSeq) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        // 判断是否是会话成员
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        if (!memberIds.contains(Long.valueOf(getUserId()))) {
            throw new BizException(BizException.USER_ERROR, "不是会话成员");
        }
        // 判断是否是私聊,只有私聊才有是否已读状态
        if (!Objects.equals(chatConversationDoc.getType(), ConversationTypeEnum.SINGLE)) {
            return Result.success();
        }
        Criteria criteria = Criteria.where("conversationId").is(conversationId).and("userId").is(Long.parseLong(getUserId()));
        ChatUserConversationDoc chatUserConversationDoc = mongoTemplate.findOne(Query.query(criteria), ChatUserConversationDoc.class);
        if (chatUserConversationDoc == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "用户会话状态不存在");
        }
        Long oldLastReadSeq = chatUserConversationDoc.getLastReadSeq();
        if (oldLastReadSeq == null) {
            oldLastReadSeq = 0L;
        }
        // 取较大值，避免请求更小导致回退
        lastReadSeq = Math.max(lastReadSeq,oldLastReadSeq);
        String lastMessageId = chatConversationDoc.getLastMessageId();
        if (StringUtils.isBlank(lastMessageId)) {
            lastReadSeq = Math.max(lastReadSeq, 0L);
        } else {
            Optional<ChatMessageDoc> optional = getById(lastMessageId);
            ChatMessageDoc lastMessageDoc = optional.orElse(null);
            if (lastMessageDoc == null) {
                throw new BizException(BizException.MESSAGE_ERROR, "消息不存在");
            }
            lastReadSeq = lastMessageDoc.getSeq() < lastReadSeq ? lastMessageDoc.getSeq() : lastReadSeq;
        }
        if (oldLastReadSeq.equals(lastReadSeq)) {
            // 已读状态不用改变
            return Result.success();
        }
        // 更新用户会话状态
        Criteria c = Criteria.where("conversationId").is(conversationId).and("userId").is(Long.parseLong(getUserId()));
        int prevUnread = chatUserConversationDoc.getUnreadCount() != null ? chatUserConversationDoc.getUnreadCount() : 0;
        long seqDelta = lastReadSeq - oldLastReadSeq;
        int nextUnread = (int) Math.max(0L, (long) prevUnread - seqDelta);
        Update up = new Update().set("lastReadSeq", lastReadSeq).set("updatedAt", LocalDateTime.now())
                .set("unreadCount", nextUnread);
        mongoTemplate.updateFirst(Query.query(c), up, ChatUserConversationDoc.class);
        // 推送消息给对方
        Long receiverId = null;
        for (Long memberId : memberIds) {
            if (!memberId.equals(Long.parseLong(getUserId()))) {
                receiverId = memberId;
                break;
            }
        }
        if (receiverId == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "消息状态异常,没查到接收方");
        }
        // 发送Mq消息
        ReadMessageMessage vo = ReadMessageMessage.builder()
                .lastReadSeq(lastReadSeq).receiverId(receiverId.toString()).conversationId(conversationId).build();
        rabbitTemplate.convertAndSend(RabbitMqConstants.READ_MESSAGE_EXCHANGE,
                RabbitMqConstants.READ_MESSAGE_ROUTING_KEY, vo);
        return Result.success();
    }

    private ChatConversationDoc getChatConversationDoc(String conversationId) {
        Optional<ChatConversationDoc> opt = chatConversationService.getById(conversationId);
        if (opt.isEmpty()) {
            throw new BizException(BizException.MESSAGE_ERROR, "会话不存在");
        }
        return opt.get();
    }
}

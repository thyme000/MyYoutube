package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.chain.message.MessageSendChain;
import cn.diaojiahong.myyoutube.chat.chain.message.MessageSendContext;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ChatMessageWithdrawDTO;
import cn.diaojiahong.myyoutube.chat.mongo.dto.MessageListPageQuery;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.RecallTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.*;
import cn.diaojiahong.myyoutube.chat.mongo.vo.FindRecordVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.MessagePage;
import cn.diaojiahong.myyoutube.chat.mongo.vo.MessagePageVO;
import cn.diaojiahong.myyoutube.chat.service.BaseMongoService;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategy;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.mq.message.ReadMessageMessage;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ChatMessageService extends BaseMongoService<ChatMessageDoc> {

    private static final Long STORAGE_EXPIRE = 3600L * 10;

    private final ChatConversationService chatConversationService;
    private final RocketMQTemplate rocketMQTemplate;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final StringRedisTemplate redisTemplate;
    private final ContentInternalPort contentInternalPort;
    private final ChatContentTypeStrategyRegistry contentTypeStrategies;
    private final SyncChainStepRegistry chainRegistry;
    private final TransactionTemplate mongoTransactionTemplate;
    public ChatMessageService(MongoTemplate mongoTemplate,
                              ChatConversationService chatConversationService,
                              RocketMQTemplate rocketMQTemplate,
                              UserPort userPort,
                              S3StorageUtil storageUtil,
                              StringRedisTemplate redisTemplate,
                              ContentInternalPort contentInternalPort,
                              ChatContentTypeStrategyRegistry contentTypeStrategies,
                              SyncChainStepRegistry chainRegistry,
                              TransactionTemplate mongoTransactionTemplate) {
        super(mongoTemplate, ChatMessageDoc.class);
        this.chatConversationService = chatConversationService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.userPort = userPort;
        this.storageUtil = storageUtil;
        this.redisTemplate = redisTemplate;
        this.contentInternalPort = contentInternalPort;
        this.contentTypeStrategies = contentTypeStrategies;
        this.chainRegistry = chainRegistry;
        this.mongoTransactionTemplate = mongoTransactionTemplate;
    }

    public Optional<ChatMessageDoc> findByIdempotent(Long senderId, String clientMsgId) {
        return Optional.ofNullable(mongoTemplate.findById(MessageSendContext.messageId(senderId, clientMsgId), ChatMessageDoc.class));
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

    public ChatMessageAckDTO send(ChatMessageDTO chatMessageDTO) {
        // 类型校验/会话与成员守卫/禁言/幂等/互关频控全部在链上, 步骤见 chat.chain.message
        MessageSendContext ctx = MessageSendContext.of(chatMessageDTO, getUserId());
        chainRegistry.execute(MessageSendChain.GROUP, ctx, MessageSendContext::hasAck);
        if (ctx.hasAck()) {
            // 该消息此前已发送过, 幂等命中直接返回旧 ack, 不再写入
            return ctx.getAck();
        }
        String userId = ctx.getUserId();
        String conversationId = ctx.getConversationId();
        ChatConversationDoc chatConversationDoc = ctx.getConversationDoc();
        boolean isInterrelated = ctx.isInterrelated();
        ChatMessageDoc chatMessageDoc = BeanUtil.toBean(chatMessageDTO, ChatMessageDoc.class);
        chatMessageDoc.setId(MessageSendContext.messageId(chatMessageDTO.getSenderId(), chatMessageDTO.getClientMsgId()));
        chatMessageDoc.setCreatedAt(LocalDateTime.now());
        chatMessageDoc.setStatus(MessageStatusEnum.SENT);
        // 转发类由策略查远端固化快照, 其余类型原样透传; 远程调用在事务外完成
        chatMessageDoc.setContent(contentTypeStrategies.get(chatMessageDoc.getContentType())
                .snapshot(chatMessageDTO.getContent()));
        // 群聊最后一条消息发送人昵称: Feign 提前到事务外查好, 事务里只做纯写
        boolean isGroup = chatConversationDoc.getType().equals(ConversationTypeEnum.GROUP);
        String senderNickname = isGroup ? userPort.getUser(Long.valueOf(userId)).getNickname() : null;
        ChatMessageDoc existing;
        try {
            existing = mongoTransactionTemplate.execute(tx -> {
                var old = findByIdempotent(chatMessageDoc.getSenderId(), chatMessageDoc.getClientMsgId()).orElse(null);
                if (old != null) return old;
                chatMessageDoc.setSeq(nextSeq(conversationId));
                mongoTemplate.insert(chatMessageDoc, "chat_messages");
                // 设置会话最后消息
                chatConversationDoc.setLastMessageAt(chatMessageDoc.getCreatedAt());
                chatConversationDoc.setLastMessageId(chatMessageDoc.getId());
                chatConversationDoc.setIsLastMessageRecall(false);
                // 系统消息等无预览类型不动 lastMessagePreview, 保留上一条媒体消息的预览
                String preview = contentTypeStrategies.get(chatMessageDoc.getContentType()).previewLabel(chatMessageDoc.getContent());
                if (preview != null) {
                    chatConversationDoc.setLastMessagePreview(preview);
                }
                if (isGroup) {
                    chatConversationDoc.setLastMessageSenderNickname(senderNickname);
                }
                mongoTemplate.save(chatConversationDoc, "chat_conversations");
                return null;
            });
        } catch (DuplicateKeyException duplicate) {
            existing = findByIdempotent(chatMessageDoc.getSenderId(), chatMessageDoc.getClientMsgId()).orElse(null);
            if (existing == null) throw duplicate;
        }
        if (existing != null) {
            if (!conversationId.equals(existing.getConversationId()))
                throw new BizException(BizException.MESSAGE_ERROR, "会话id不一致");
            return getAck(existing, existing.getStatus());
        }
        if (chatMessageDoc.getContentType().isForward())
            incrementShareCountQuietly(chatMessageDoc.getContent().getContentId(), Long.valueOf(userId));
        // ack 与推送报文依赖 save 后的 messageId/seq, 必须在提交后构建
        ChatMessageAckDTO chatMessageAckDTO = getAck(chatMessageDoc, MessageStatusEnum.SENT);
        ChatMessageMessage ch = ChatMessageMessage.builder().senderId(chatMessageDoc.getSenderId())
                .seq(chatMessageDoc.getSeq()).conversationId(conversationId)
                .messageId(chatMessageDoc.getId()).contentType(chatMessageDTO.getContentType())
                .serviceTime(chatMessageDoc.getCreatedAt()).event("send").build();
        if (chatMessageDTO.getContentType().isText()) {
            ch.setContent(chatMessageDTO.getContent().getText());
        }
        if (chatConversationDoc.getType().equals(ConversationTypeEnum.SINGLE)) {
            // 私聊并且不是互关,增加消息次数; 提交后再计, 回滚的消息不计数
            if (!isInterrelated) {
                redisTemplate.opsForZSet().incrementScore(RedisConstants.USER_CONVERSATION + userId, conversationId, 1);
                redisTemplate.expire(RedisConstants.USER_CONVERSATION + userId,
                        RedisConstants.USER_CONVERSATION_EXPIRE, TimeUnit.DAYS);
            }
        }
        rocketMQTemplate.syncSend(MqConstants.TOPIC_CHAT_MESSAGE, ch);
        return chatMessageAckDTO;
    }

    private ChatMessageAckDTO getAck(ChatMessageDoc chatMessageDoc, MessageStatusEnum status) {
        return MessageSendContext.ackOf(chatMessageDoc, status);
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

    public ChatMessageAckDTO withdraw(ChatMessageWithdrawDTO chatMessageWithdrawDTO) {
        String userId = getUserId();
        if (chatMessageWithdrawDTO.getClientMsgId() == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "客户端幂等id不能为空");
        }
        Optional<ChatMessageDoc> existed =
                findByIdempotent(chatMessageWithdrawDTO.getSenderId(), chatMessageWithdrawDTO.getClientMsgId());
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
            return getAck(chatMessageDoc, chatMessageDoc.getStatus());
        }
        Criteria c = Criteria.where("conversationId").is(chatMessageDoc.getConversationId()).and("id").is(chatMessageDoc.getId());
        Update up = new Update().set("recalled", true).set("recalledAt", recallTime).set("status", MessageStatusEnum.WITHDRAWN);
        Long operatorId = Long.valueOf(userId);
        // 必须先判断「本人撤自己」：群主也是 createdBy，否则会被误判为 ADMIN
        RecallTypeEnum recallTypeForMq;
        if (chatMessageDoc.getSenderId() != null && chatMessageDoc.getSenderId().equals(operatorId)) {
            // 本人撤回自己发送的消息；群聊群主撤回自己的消息不受 2 分钟限制
            boolean groupOwnerRecallOwn =
                    ConversationTypeEnum.GROUP.equals(chatConversationDoc.getType())
                            && createdBy != null
                            && createdBy.equals(operatorId);
            if (!groupOwnerRecallOwn && chatMessageDoc.getCreatedAt().isBefore(recallTime.minusMinutes(2))) {
                throw new BizException(BizException.MESSAGE_ERROR, "消息已发送超过2分钟,不能撤回");
            }
            up.set("recallType", RecallTypeEnum.SENDER);
            recallTypeForMq = RecallTypeEnum.SENDER;
        } else if (createdBy != null && createdBy.equals(operatorId)) {
            // 群主撤回他人消息
            up.set("recallType", RecallTypeEnum.ADMIN);
            recallTypeForMq = RecallTypeEnum.ADMIN;
        } else {
            throw new BizException(BizException.MESSAGE_ERROR, "无法确定撤回类型");
        }
        mongoTransactionTemplate.executeWithoutResult(tx -> {
            mongoTemplate.updateFirst(Query.query(c), up, ChatMessageDoc.class);
            // 设置会话最后消息撤回
            chatConversationDoc.setIsLastMessageRecall(true);
            mongoTemplate.save(chatConversationDoc, "chat_conversations");
        });
        // 推送消息到所有成员: 提交后发, MQ 失败不回滚撤回
        ChatMessageMessage ch = ChatMessageMessage.builder().senderId(chatMessageDoc.getSenderId())
                .seq(chatMessageDoc.getSeq()).conversationId(chatMessageDoc.getConversationId())
                .messageId(chatMessageDoc.getId()).contentType(chatMessageDoc.getContentType())
                .recallType(recallTypeForMq)
                .serviceTime(recallTime).event("withdraw").build();
        rocketMQTemplate.syncSend(MqConstants.TOPIC_CHAT_MESSAGE, ch);
        return getAck(chatMessageDoc, MessageStatusEnum.WITHDRAWN);
    }

    public MessagePageVO getMessageList(String conversationId, MessageListPageQuery messageListPageQuery) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        // 判断是否是会话成员
        if (!memberIds.contains(Long.valueOf(getUserId()))) {
            throw new BizException(BizException.USER_ERROR, "不是会话成员");
        }
        // 用户信息
        List<UserSummary> members = userPort.listByIds(memberIds);
        Map<Long, String> authorNicknames = UserSummaries.toNicknameMap(members);
        Map<Long, String> authorAvatarUrls = UserSummaries.toAvatarMap(members);
        // 分页查询
        Page<ChatMessageDoc> page = pageByConversation(conversationId, messageListPageQuery.getSeq(), messageListPageQuery.getSize());
        List<MessagePage> result = getMessagePageVOS(page, authorNicknames, authorAvatarUrls);
        MessagePageVO vo = MessagePageVO.builder().messages(result).nextSeq(page.hasNext() ? page.getContent().get(0).getSeq() : null).hasMore(page.hasNext()).build();
        return vo;
    }

    private List<MessagePage> getMessagePageVOS(Page<ChatMessageDoc> page, Map<Long, String> authorNicknames, Map<Long, String> authorAvatarUrls) {
        List<ChatMessageDoc> docs = page.getContent();
        return docs.stream().map(doc -> {
            MessagePage vo = new MessagePage();
            vo.setSenderId(doc.getSenderId());
            vo.setSenderNickName(authorNicknames.get(doc.getSenderId()));
            vo.setSenderAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(doc.getSenderId()), STORAGE_EXPIRE));
            vo.setContentType(doc.getContentType());
            ChatContentTypeStrategy contentTypeStrategy = contentTypeStrategies.get(doc.getContentType());
            if (contentTypeStrategy != null) {
                contentTypeStrategy.signUrls(doc.getContent(), STORAGE_EXPIRE);
            }
            vo.setContent(doc.getContent());
            vo.setMessageId(doc.getId());
            vo.setClientMsgId(doc.getClientMsgId());
            vo.setSeq(doc.getSeq());
            vo.setStatus(doc.getStatus());
            vo.setCreatedAt(doc.getCreatedAt());
            vo.setRecallType(doc.getRecallType());
            return vo;
        }).toList();
    }

    public MessagePageVO syncMessageList(String conversationId, @Valid MessageListPageQuery messageListPageQuery) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        // 判断是否是会话成员
        if (!memberIds.contains(Long.valueOf(getUserId()))) {
            throw new BizException(BizException.USER_ERROR, "不是会话成员");
        }
        // 用户信息
        List<UserSummary> members = userPort.listByIds(memberIds);
        Map<Long, String> authorNicknames = UserSummaries.toNicknameMap(members);
        Map<Long, String> authorAvatarUrls = UserSummaries.toAvatarMap(members);
        // 分页查询
        Page<ChatMessageDoc> page = syncPageByConversation(conversationId, messageListPageQuery.getSeq(), messageListPageQuery.getSize());
        List<MessagePage> result = getMessagePageVOS(page, authorNicknames, authorAvatarUrls);
        MessagePageVO vo = MessagePageVO.builder().messages(result).nextSeq(page.hasNext() ? page.getContent().get(0).getSeq() : null).hasMore(page.hasNext()).build();
        return vo;
    }

    public void read(String conversationId, Long lastReadSeq) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        // 判断是否是会话成员
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        if (!memberIds.contains(Long.valueOf(getUserId()))) {
            throw new BizException(BizException.USER_ERROR, "不是会话成员");
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
            return;
        }
        // 更新用户会话状态; 单文档 updateFirst 自带原子性, 不开事务
        Criteria c = Criteria.where("conversationId").is(conversationId).and("userId").is(Long.parseLong(getUserId()));
        int prevUnread = chatUserConversationDoc.getUnreadCount() != null ? chatUserConversationDoc.getUnreadCount() : 0;
        long seqDelta = lastReadSeq - oldLastReadSeq;
        int nextUnread = (int) Math.max(0L, (long) prevUnread - seqDelta);
        Update up = new Update().set("lastReadSeq", lastReadSeq).set("updatedAt", LocalDateTime.now())
                .set("unreadCount", nextUnread);
        mongoTemplate.updateFirst(Query.query(c), up, ChatUserConversationDoc.class);
        // 判断是否是私聊,只有私聊才有是否已读状态,才需要推送消息
        if (!Objects.equals(chatConversationDoc.getType(), ConversationTypeEnum.SINGLE)) {
            return;
        }
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
        rocketMQTemplate.syncSend(MqConstants.TOPIC_CHAT_READ, vo);
    }

    private ChatConversationDoc getChatConversationDoc(String conversationId) {
        Optional<ChatConversationDoc> opt = chatConversationService.getById(conversationId);
        if (opt.isEmpty()) {
            throw new BizException(BizException.MESSAGE_ERROR, "会话不存在");
        }
        return opt.get();
    }

    public PageResult<FindRecordVO> findRecord(String conversationId, String keyword, Integer pageNum, Integer pageSize) {
        ChatConversationDoc conv = getChatConversationDoc(conversationId);
        List<Long> memberIds = conv.getMemberIds();
        if (!memberIds.contains(Long.valueOf(getUserId()))) {
            throw new BizException(BizException.USER_ERROR, "不是会话成员");
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return PageResult.of(0L, 0L, List.of());
        }
        int pn = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int ps = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 50);
        // 字面量匹配，避免 keyword 里的正则元字符出问题
        Pattern p = Pattern.compile(Pattern.quote(kw), Pattern.CASE_INSENSITIVE);
        Criteria c = Criteria.where("conversationId").is(conversationId)
                .andOperator(new Criteria().orOperator(
                        Criteria.where("content.text").regex(p),
                        Criteria.where("content.systemText").regex(p),
                        Criteria.where("content.fileName").regex(p)
                )).and("recalled").ne(true);
        Query countQ = Query.query(c);
        long total = mongoTemplate.count(countQ, ChatMessageDoc.class);
        if (total == 0L) {
            return PageResult.of(0L, 0L, List.of());
        }
        long pages = (total + ps - 1) / ps;
        Query q = Query.query(c)
                .with(PageRequest.of(pn - 1, ps, Sort.by(Sort.Direction.DESC, "seq")));
        List<ChatMessageDoc> docs = mongoTemplate.find(q, ChatMessageDoc.class);
        List<UserSummary> members = userPort.listByIds(memberIds);
        Map<Long, String> nickMap = UserSummaries.toNicknameMap(members);
        Map<Long, String> avMap = UserSummaries.toAvatarMap(members);
        List<FindRecordVO> list = new ArrayList<>(docs.size());
        for (ChatMessageDoc doc : docs) {
            FindRecordVO vo = new FindRecordVO();
            vo.setSeq(doc.getSeq());
            vo.setContentType(doc.getContentType());
            vo.setCreatedAt(doc.getCreatedAt());
            vo.setSenderNickname(nickMap.get(doc.getSenderId()));
            vo.setSenderAvatarUrl(storageUtil.signGetUrl(avMap.get(doc.getSenderId()), STORAGE_EXPIRE));
            ChatContent content = doc.getContent();
            ChatContentTypeStrategy contentTypeStrategy = contentTypeStrategies.get(doc.getContentType());
            if (content != null && contentTypeStrategy != null) {
                contentTypeStrategy.signUrls(content, STORAGE_EXPIRE);
            }
            vo.setContent(content);
            list.add(vo);
        }
        return PageResult.of(total, pages, list);
    }

    // 消息已提交后计数；幂等命中在 send 入口返回，不重复执行此副作用。
    private void incrementShareCountQuietly(Long contentId, Long viewerId) {
        try {
            if (!contentInternalPort.incrementShareCount(contentId, viewerId))
                log.error("更新内容分享数失败,内容id:{}", contentId);
        } catch (Exception e) {
            log.error("更新内容分享数异常,内容id:{}", contentId, e);
        }
    }
}

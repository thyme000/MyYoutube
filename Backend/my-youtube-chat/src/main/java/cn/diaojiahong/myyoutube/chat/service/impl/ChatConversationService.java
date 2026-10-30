package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.chat.mongo.dto.CreateConversationDTO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.AnnouncementPublishVO;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ConversationMembersVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ConversationVO;
import cn.diaojiahong.myyoutube.chat.service.BaseMongoService;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.mq.message.GroupDeletedMessage;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatConversationService extends BaseMongoService<ChatConversationDoc> {

    private static final String BIZ_PREFIX = "chat";

    private final FollowPort followPort;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final RocketMQTemplate rocketMQTemplate;
    private final PushFanoutPublisher pushFanoutPublisher;
    private final TransactionTemplate mongoTransactionTemplate;

    public ChatConversationService(MongoTemplate mongoTemplate,
                                   FollowPort followPort,
                                   UserPort userPort,
                                   S3StorageUtil storageUtil,
                                   RocketMQTemplate rocketMQTemplate,
                                   PushFanoutPublisher pushFanoutPublisher,
                                   TransactionTemplate mongoTransactionTemplate) {
        super(mongoTemplate, ChatConversationDoc.class);
        this.followPort = followPort;
        this.userPort = userPort;
        this.storageUtil = storageUtil;
        this.rocketMQTemplate = rocketMQTemplate;
        this.pushFanoutPublisher = pushFanoutPublisher;
        this.mongoTransactionTemplate = mongoTransactionTemplate;
    }

    @ObservedBusiness(BusinessOperation.CREATE_CONVERSATION)
    public ConversationVO createConversation(CreateConversationDTO createConversationDTO) {
        Long userId = getUserId();
        // 判断是否是互关用户 -> 不是互关用户则返回错误; Feign 守卫在事务外, 事务里只做纯写
        List<Long> memberIds = createConversationDTO.getMemberIds();
        if (createConversationDTO.getType() == ConversationTypeEnum.GROUP) {
            if (!followPort.interrelated(new FollowPort.InterrelatedQuery(userId, memberIds))) {
                throw new BizException(ErrorCode.FORBIDDEN, "不是互关用户");
            }
            // 刚创建的群聊将使用前端默认头像为会话头像url
        }
        boolean isSingle = createConversationDTO.getType() == ConversationTypeEnum.SINGLE;
        // 判断是否和需要创建的用户已经有会话了 -> 已有则返回提示
        if (isSingle) {
            if (memberIds == null || memberIds.size() != 1) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "私聊请选择一位对方用户");
            }
            Long peerId = memberIds.get(0);
            if (peerId == null || peerId.equals(userId)) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "不能与自己发起私聊");
            }
            Query dup = new Query(
                    Criteria.where("type").is(ConversationTypeEnum.SINGLE)
                            .andOperator(
                                    Criteria.where("memberIds").size(2),
                                    Criteria.where("memberIds").all(userId, peerId)
                            )
            );
            if (mongoTemplate.exists(dup, ChatConversationDoc.class)) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "与该用户已有私聊会话");
            }
        }
        memberIds.add(userId);
        // 创建聊天会话表
        ChatConversationDoc chatConversationDoc = new ChatConversationDoc();
        chatConversationDoc.setType(createConversationDTO.getType());
        chatConversationDoc.setMemberIds(memberIds);
        chatConversationDoc.setName(isSingle ? null : createConversationDTO.getName());
        chatConversationDoc.setCreatedBy(userId);
        chatConversationDoc.setAvatar(isSingle ? null : createConversationDTO.getAvatar());
        chatConversationDoc.setBanned(false);
        chatConversationDoc.setAnnouncement(null);
        chatConversationDoc.setAnnouncementVersion(0);
        chatConversationDoc.setIsLastMessageRecall(false);
        chatConversationDoc.setLastMessageId(null);
        chatConversationDoc.setLastMessagePreview(null);
        chatConversationDoc.setLastMessageSenderNickname(null);
        chatConversationDoc.setLastMessageAt(null);
        // 创建用户会话状态表
        LocalDateTime now = LocalDateTime.now();
        List<ChatUserConversationDoc> chatUserConversationDocs = new ArrayList<>();
        for (Long memberId : memberIds) {
            ChatUserConversationDoc chatUserConversationDoc = new ChatUserConversationDoc();
            chatUserConversationDoc.setUserId(memberId);
            chatUserConversationDoc.setUnreadCount(0);
            chatUserConversationDoc.setLastReadSeq(0L);
            chatUserConversationDoc.setMute(false);
            chatUserConversationDoc.setPin(false);
            chatUserConversationDoc.setDeleted(false);
            chatUserConversationDoc.setBanned(false);
            chatUserConversationDoc.setLastPullAt(now);
            chatUserConversationDoc.setCreatedAt(now);
            chatUserConversationDoc.setUpdatedAt(now);
            chatUserConversationDocs.add(chatUserConversationDoc);
        }
        // 会话与成员会话状态要么都在要么都不在
        mongoTransactionTemplate.executeWithoutResult(tx -> {
            save(chatConversationDoc);
            chatUserConversationDocs.forEach(member -> member.setConversationId(chatConversationDoc.getId()));
            mongoTemplate.insert(chatUserConversationDocs, "chat_user_conversation");
        });
        // 构建VO
        ConversationVO conversationVO = new ConversationVO();
        conversationVO.setId(chatConversationDoc.getId());
        conversationVO.setType(createConversationDTO.getType());
        conversationVO.setMemberIds(memberIds);
        conversationVO.setName(createConversationDTO.getName());
        log.info("Conversation created conversationId={} type={} creatorId={} memberCount={}",
                chatConversationDoc.getId(), createConversationDTO.getType(), userId, memberIds.size());
        return conversationVO;
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            return null;
        }

        return Long.parseLong(userId);
    }

    public String updateConversationAvatar(String conversationId, MultipartFile avatar) {
        Long userId = getUserId();
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能修改头像");
        }

        if (!chatConversationDoc.getCreatedBy().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }

        // 上传文件到对象存储
        String avatarUrl = null;
        try {
            byte[] bytes = avatar.getBytes();
            avatarUrl = storageUtil.upload(bytes, avatar.getOriginalFilename(), userId, BIZ_PREFIX);
        } catch (IOException e) {
            log.error("上传文件到对象存储失败，avatar: {}", avatar.getOriginalFilename(), e);
        }
        // 更新会话头像url
        chatConversationDoc.setAvatar(avatarUrl);
        save(chatConversationDoc);
        return avatarUrl;
    }

    public String updateConversationName(String conversationId, String name) {
        Long userId = getUserId();
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能修改名称");
        }

        if (!chatConversationDoc.getCreatedBy().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }

        // 更新会话名称
        chatConversationDoc.setName(name);
        save(chatConversationDoc);
        return name;
    }

    /**
     * 发布/更新群公告：仅群主（创建人），写入会话文档并递增版本；经 WebSocket 通知成员。
     */
    public AnnouncementPublishVO publishAnnouncement(String conversationId, String text) {
        Long userId = getUserId();
        ChatConversationDoc doc = getChatConversationDoc(conversationId);
        if (doc.getType() != ConversationTypeEnum.GROUP) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "仅群聊可发布公告");
        }

        if (doc.getCreatedBy() == null || !doc.getCreatedBy().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅群主可发布公告");
        }

        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "公告内容不能为空");
        }

        int prev = doc.getAnnouncementVersion() == null ? 0 : doc.getAnnouncementVersion();
        int next = prev + 1;
        LocalDateTime now = LocalDateTime.now();
        doc.setAnnouncement(t);
        doc.setAnnouncementVersion(next);
        doc.setUpdatedAt(now);
        save(doc);

        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("type", "ANNOUNCEMENT_UPDATED");
        ws.put("conversationId", conversationId);
        ws.put("version", next);
        String json = JsonUtil.toJson(ws);
        List<Long> memberIds = doc.getMemberIds();
        if (memberIds != null) {
            for (Long memberId : memberIds) {
                if (memberId != null && !memberId.equals(userId)) {
                    pushFanoutPublisher.pushWs(memberId, json);
                }
            }
        }
        return new AnnouncementPublishVO(conversationId, next, t);
    }

    public String deleteConversation(String conversationId) {
        Long userId = getUserId();
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        if (!memberIds.contains(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是会话成员");
        }

        Criteria c = Criteria.where("conversationId").is(conversationId);
        boolean dissolved = false;
        if (chatConversationDoc.getType() == ConversationTypeEnum.GROUP) {
            // 判断是否是创建人 -> 解散群
            if (chatConversationDoc.getCreatedBy().equals(userId)) {
                dissolved = true;
                // 把群成员的会话状态设置为已删除
                Update update = new Update().set("deleted", true);
                mongoTemplate.updateMulti(new Query(c), update, ChatUserConversationDoc.class);
                // 通知群成员会话已删除
                memberIds.remove(userId);
                GroupDeletedMessage message = new GroupDeletedMessage(conversationId, memberIds);
                rocketMQTemplate.syncSend(MqConstants.TOPIC_CHAT_GROUP_DELETED, message);
            } else {
                // 非创建人 -> 退出群
                c.and("userId").is(userId);
                Update update = new Update().set("deleted", true);
                mongoTemplate.updateFirst(new Query(c), update, ChatUserConversationDoc.class);
            }
        } else if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            c.and("userId").in(memberIds);
            Update update = new Update().set("deleted", true);
            mongoTemplate.updateMulti(new Query(c), update, ChatUserConversationDoc.class);
        }
        log.info("Conversation deleted conversationId={} type={} operatorId={} dissolved={}",
                conversationId, chatConversationDoc.getType(), userId, dissolved);
        return "删除成功";
    }

    @Transactional(transactionManager = "mongoTransactionManager", rollbackFor = Exception.class)
    public void removeConversationMember(String conversationId, List<Long> removeUserIds) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能移除成员");
        }

        if (!chatConversationDoc.getCreatedBy().equals(getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }

        // 移除会话成员
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        memberIds.removeAll(removeUserIds);
        chatConversationDoc.setMemberIds(memberIds);
        save(chatConversationDoc);
        // 修改用户会话状态为已删除
        Criteria c = Criteria.where("conversationId").is(conversationId).and("userId").in(removeUserIds);
        Update update = new Update().set("deleted", true);
        mongoTemplate.updateMulti(new Query(c), update, ChatUserConversationDoc.class);
    }

    private ChatConversationDoc getChatConversationDoc(String conversationId) {
        Optional<ChatConversationDoc> opt = getById(conversationId);
        if (opt.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }

        return opt.get();
    }

    public void inviteConversationMember(String conversationId, Long invitedUserId) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        Long userId = getUserId();
        // Feign 守卫提前到事务外: 用户存在性 + 互关关系
        UserSummary user = userPort.getUser(invitedUserId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        List<Long> memberIds = chatConversationDoc.getMemberIds();
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能邀请成员");
        }

        if (!memberIds.contains(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群的成员");
        }

        // 判断是否是互关关系
        if (!followPort.interrelated(new FollowPort.InterrelatedQuery(userId, List.of(invitedUserId)))) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是互关用户,无法邀请对方");
        }

        // 邀请会话成员
        memberIds.add(invitedUserId);
        chatConversationDoc.setMemberIds(memberIds);
        // 新增被邀请用户的会话状态信息
        ChatUserConversationDoc userConversationDoc = new ChatUserConversationDoc();
        userConversationDoc.setUserId(invitedUserId);
        userConversationDoc.setConversationId(conversationId);
        userConversationDoc.setUnreadCount(0);
        userConversationDoc.setLastReadSeq(0L);
        userConversationDoc.setMute(false);
        userConversationDoc.setPin(false);
        userConversationDoc.setDeleted(false);
        userConversationDoc.setBanned(false);
        LocalDateTime now = LocalDateTime.now();
        userConversationDoc.setLastPullAt(now);
        userConversationDoc.setCreatedAt(now);
        userConversationDoc.setUpdatedAt(now);
        // 成员表与会话状态要么都在要么都不在
        mongoTransactionTemplate.executeWithoutResult(tx -> {
            save(chatConversationDoc);
            mongoTemplate.insert(userConversationDoc, "chat_user_conversation");
        });
    }

    public List<ConversationMembersVO> getConversationMembers(String conversationId) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能查询成员");
        }

        // 一次批量取摘要, 按成员顺序本地投影
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        Map<Long, UserSummary> userById = userPort.listByIds(memberIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserSummary::getId, u -> u, (a, b) -> a));
        List<ConversationMembersVO> conversationMembersVOs = new ArrayList<>();
        for (Long userId : memberIds) {
            UserSummary user = userById.get(userId);
            if (user == null) {
                continue;
            }
            conversationMembersVOs.add(new ConversationMembersVO(user.getId(),
                    user.getNickname(), storageUtil.signGetUrl(user.getAvatarUrl(), 600),
                    userId.equals(chatConversationDoc.getCreatedBy())));
        }
        return conversationMembersVOs;
    }

    public void banConversation(String conversationId, Boolean banned) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能开启全员禁言");
        }

        if (!chatConversationDoc.getCreatedBy().equals(getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }

        // 开启或关闭全员禁言
        chatConversationDoc.setBanned(banned);
        save(chatConversationDoc);
        // 发送通知给所有群成员
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        memberIds.remove(getUserId());
        Map<String, Object> data = new HashMap<>(2);
        data.put("banned", banned);
        data.put("type", "conversation");
        data.put("conversationId", conversationId);
        String json = JsonUtil.toJson(data);
        for (Long memberId : memberIds) {
            pushFanoutPublisher.pushWs(memberId, json);
        }
    }

    public AnnouncementPublishVO getAnnouncement(String conversationId) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊没有公告");
        }

        if (!chatConversationDoc.getMemberIds().contains(getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群的成员");
        }

        if (chatConversationDoc.getAnnouncement() == null) {
            return new AnnouncementPublishVO(conversationId, 0, null);
        }

        return new AnnouncementPublishVO(conversationId, chatConversationDoc.getAnnouncementVersion(),
                chatConversationDoc.getAnnouncement());
    }

    public void banConversationMember(Long userId, String conversationId, Boolean banned) {
        // 判断是否有权限
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (!chatConversationDoc.getMemberIds().contains(getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群的成员");
        }

        if (!chatConversationDoc.getCreatedBy().equals(getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }

        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能禁言成员");
        }

        // 禁言或取消禁言会话成员
        Criteria c = Criteria.where("conversationId").is(conversationId).and("userId").is(userId);
        mongoTemplate.updateFirst(Query.query(c),
                new Update().set("banned", banned), "chat_user_conversation");
        // 发送通知给对方
        Map<String, Object> data = new HashMap<>(2);
        data.put("banned", banned);
        data.put("type", "member");
        data.put("conversationId", conversationId);
        String json = JsonUtil.toJson(data);
        pushFanoutPublisher.pushWs(userId, json);
    }
}

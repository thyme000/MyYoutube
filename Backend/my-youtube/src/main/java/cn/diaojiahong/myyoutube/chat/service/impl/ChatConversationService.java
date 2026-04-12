package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.mongo.dto.CreateConversationDTO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.AnnouncementPublishVO;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ConversationMembersVO;
import cn.diaojiahong.myyoutube.chat.mongo.vo.ConversationVO;
import cn.diaojiahong.myyoutube.chat.service.BaseMongoService;
import cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants;
import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class ChatConversationService extends BaseMongoService<ChatConversationDoc> {

    private static final String BIZ_PREFIX = "chat";

    private final IUserFollowService userFollowService;
    private final IUserService userService;
    private final AliOssUtil aliOssUtil;
    private final RabbitTemplate rabbitTemplate;
    private final NotifyWebSocketHandler notifyWebSocketHandler;

    public ChatConversationService(MongoTemplate mongoTemplate,
                                   IUserFollowService userFollowService,
                                   IUserService userService,
                                   AliOssUtil aliOssUtil,
                                   RabbitTemplate rabbitTemplate,
                                   NotifyWebSocketHandler notifyWebSocketHandler) {
        super(mongoTemplate, ChatConversationDoc.class);
        this.userFollowService = userFollowService;
        this.userService = userService;
        this.aliOssUtil = aliOssUtil;
        this.rabbitTemplate = rabbitTemplate;
        this.notifyWebSocketHandler = notifyWebSocketHandler;
    }

    @Transactional(transactionManager = "mongoTransactionManager", rollbackFor = Exception.class)
    public Result<ConversationVO> createConversation(CreateConversationDTO createConversationDTO) {
        Long userId = getUserId();
        // 判断是否是互关用户 -> 不是互关用户则返回错误
        List<Long> memberIds = createConversationDTO.getMemberIds();
        if (createConversationDTO.getType() == ConversationTypeEnum.GROUP) {
            if (!userFollowService.interrelated(userId, memberIds)) {
                return Result.fail("不是互关用户");
            }
            // 刚创建的群聊将使用前端默认头像为会话头像url
        }
        boolean isSingle = createConversationDTO.getType() == ConversationTypeEnum.SINGLE;
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
        save(chatConversationDoc);
        // 创建用户会话状态表
        LocalDateTime now = LocalDateTime.now();
        List<ChatUserConversationDoc> chatUserConversationDocs = new ArrayList<>();
        for (Long memberId : memberIds) {
            ChatUserConversationDoc chatUserConversationDoc = new ChatUserConversationDoc();
            chatUserConversationDoc.setConversationId(chatConversationDoc.getId());
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
        mongoTemplate.insert(chatUserConversationDocs, "chat_user_conversation");
        // 构建VO
        ConversationVO conversationVO = new ConversationVO();
        conversationVO.setId(chatConversationDoc.getId());
        conversationVO.setType(createConversationDTO.getType());
        conversationVO.setMemberIds(memberIds);
        conversationVO.setName(createConversationDTO.getName());
        return Result.success(conversationVO);
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        return Long.parseLong(userId);
    }

    public Result<String> updateConversationAvatar(String conversationId, MultipartFile avatar) {
        Long userId = getUserId();
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new IllegalArgumentException("私聊不能修改头像");
        }
        if (!chatConversationDoc.getCreatedBy().equals(userId)) {
            throw new IllegalArgumentException("不是创建人");
        }
        // 上传文件到OSS
        String avatarUrl = null;
        try {
            byte[] bytes = avatar.getBytes();
            avatarUrl = aliOssUtil.upload(bytes, avatar.getOriginalFilename(), userId, BIZ_PREFIX);
        } catch (IOException e) {
            log.error("上传文件到OSS失败，avatar: {}", avatar.getOriginalFilename(), e);
        }
        // 更新会话头像url
        chatConversationDoc.setAvatar(avatarUrl);
        save(chatConversationDoc);
        return Result.success(avatarUrl);
    }

    public Result<String> updateConversationName(String conversationId, String name) {
        Long userId = getUserId();
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new IllegalArgumentException("私聊不能修改名称");
        }
        if (!chatConversationDoc.getCreatedBy().equals(userId)) {
            throw new IllegalArgumentException("不是创建人");
        }
        // 更新会话名称
        chatConversationDoc.setName(name);
        save(chatConversationDoc);
        return Result.success(name);
    }

    /**
     * 发布/更新群公告：仅群主（创建人），写入会话文档并递增版本；经 WebSocket 通知成员刷新。
     */
    public Result<AnnouncementPublishVO> publishAnnouncement(String conversationId, String text) {
        Long userId = getUserId();
        ChatConversationDoc doc = getChatConversationDoc(conversationId);
        if (doc.getType() != ConversationTypeEnum.GROUP) {
            throw new IllegalArgumentException("仅群聊可发布公告");
        }
        if (doc.getCreatedBy() == null || !doc.getCreatedBy().equals(userId)) {
            throw new IllegalArgumentException("仅群主可发布公告");
        }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("公告内容不能为空");
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
        String json = new GsonBuilder().create().toJson(ws);
        List<Long> memberIds = doc.getMemberIds();
        if (memberIds != null) {
            for (Long memberId : memberIds) {
                if (memberId != null && !memberId.equals(userId)) {
                    notifyWebSocketHandler.sendToUser(memberId, json);
                }
            }
        }
        return Result.success(new AnnouncementPublishVO(conversationId, next, t));
    }

    public Result<String> deleteConversation(String conversationId) {
        Long userId = getUserId();
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        if (!memberIds.contains(userId)) {
            throw new IllegalArgumentException("不是会话成员");
        }
        Criteria c = Criteria.where("conversationId").is(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.GROUP) {
            // 判断是否是创建人 -> 解散群
            if (chatConversationDoc.getCreatedBy().equals(userId)) {
                // 把群成员的会话状态设置为已删除
                Update update = new Update().set("deleted", true);
                mongoTemplate.updateMulti(new Query(c), update, ChatUserConversationDoc.class);
                // 通知群成员会话已删除
                rabbitTemplate.convertAndSend(RabbitMqConstants.NOTIFY_GROUP_MEMBER_DELETED_EXCHANGE,
                        RabbitMqConstants.NOTIFY_GROUP_MEMBER_DELETED_ROUTING_KEY, conversationId);
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
        return Result.success("删除成功");
    }

    @Transactional(transactionManager = "mongoTransactionManager", rollbackFor = Exception.class)
    public Result<Void> removeConversationMember(String conversationId, List<Long> removeUserIds) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new IllegalArgumentException("私聊不能移除成员");
        }
        if (!chatConversationDoc.getCreatedBy().equals(getUserId())) {
            throw new IllegalArgumentException("不是创建人");
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
        return Result.success(null);
    }

    private ChatConversationDoc getChatConversationDoc(String conversationId) {
        Optional<ChatConversationDoc> opt = getById(conversationId);
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("会话不存在");
        }
        return opt.get();
    }

    @Transactional(transactionManager = "mongoTransactionManager", rollbackFor = Exception.class)
    public Result<Void> inviteConversationMember(String conversationId, Long invitedUserId) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        Long userId = getUserId();
        User user = userService.getById(invitedUserId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        List<Long> memberIds = chatConversationDoc.getMemberIds();
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new IllegalArgumentException("私聊不能邀请成员");
        }
        if (!memberIds.contains(userId)) {
            return Result.fail("你不是该群的成员");
        }
        // 判断是否是互关关系
        if (!userFollowService.interrelated(userId, List.of(invitedUserId))) {
            return Result.fail("不是互关用户,无法邀请对方");
        }
        // 邀请会话成员
        memberIds.add(invitedUserId);
        chatConversationDoc.setMemberIds(memberIds);
        save(chatConversationDoc);
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
        mongoTemplate.insert(userConversationDoc, "chat_user_conversation");
        return Result.success(null);
    }

    public Result<List<ConversationMembersVO>> getConversationMembers(String conversationId) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new IllegalArgumentException("私聊不能查询成员");
        }
        List<ConversationMembersVO> conversationMembersVOs = new ArrayList<>();
        for (Long userId : chatConversationDoc.getMemberIds()) {
            User user = userService.getById(userId);
            conversationMembersVOs.add(new ConversationMembersVO(user.getId(),
                    user.getNickname(), aliOssUtil.signGetUrl(user.getAvatarUrl(), 600),
                    userId.equals(chatConversationDoc.getCreatedBy())));
        }
        return Result.success(conversationMembersVOs);
    }

    public Result<Void> banConversation(String conversationId, Boolean banned) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new IllegalArgumentException("私聊不能开启全员禁言");
        }
        if (!chatConversationDoc.getCreatedBy().equals(getUserId())) {
            throw new IllegalArgumentException("不是创建人");
        }
        // 开启或关闭全员禁言
        chatConversationDoc.setBanned(banned);
        save(chatConversationDoc);
        return Result.success(null);
    }

    public Result<AnnouncementPublishVO> getAnnouncement(String conversationId) {
        ChatConversationDoc chatConversationDoc = getChatConversationDoc(conversationId);
        if (chatConversationDoc.getType() == ConversationTypeEnum.SINGLE) {
            throw new IllegalArgumentException("私聊没有公告");
        }
        if (!chatConversationDoc.getMemberIds().contains(getUserId())) {
            return Result.fail("你不是该群的成员");
        }
        if (chatConversationDoc.getAnnouncement() == null) {
            return Result.success(new AnnouncementPublishVO(conversationId, 0, null));
        }
        return Result.success(new AnnouncementPublishVO(conversationId, chatConversationDoc.getAnnouncementVersion(),
                chatConversationDoc.getAnnouncement()));
    }
}

package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.notify.AbstractConversationNotifyStrategy;
import cn.diaojiahong.myyoutube.chat.domain.dto.CreateConversationDTO;
import cn.diaojiahong.myyoutube.chat.domain.vo.AnnouncementPublishVO;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationMembersVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationVO;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.idempotent.DuplicatePolicy;
import cn.diaojiahong.myyoutube.common.idempotent.Idempotent;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.mq.message.GroupDeletedMessage;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final String BIZ_PREFIX = "chat";
    private static final long MEMBER_LIST_AVATAR_EXPIRE_SECONDS = 10 * 60;

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper conversationMemberMapper;
    private final PrivateConversationService privateConversationService;
    private final FollowPort followPort;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final RocketMQTemplate rocketMQTemplate;
    private final PushFanoutPublisher pushFanoutPublisher;
    private final TransactionTemplate transactionTemplate;

    @ObservedBusiness(BusinessOperation.CREATE_CONVERSATION)
    public ConversationVO createConversation(CreateConversationDTO createConversationDTO) {
        if (createConversationDTO.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "该接口仅用于创建群聊");
        }
        Long userId = getUserId();
        // 客户端成员列表是不可信输入: 先剔 null、去重, 再按会话类型做各自清洗
        List<Long> memberIds = createConversationDTO.getMemberIds() == null
                ? new ArrayList<>()
                : createConversationDTO.getMemberIds().stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toCollection(ArrayList::new));

        requireGroupName(createConversationDTO.getName());
        // 群聊把创建人从候选里摘掉: 互关检查不应包含自己, 创建人稍后统一加回
        memberIds.removeIf(memberId -> memberId.equals(userId));
        if (memberIds.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "群聊请至少选择一位成员");
        }
        // 用户不存在要显式点名报错, 不能静默丢弃后让用户以为群建对了
        List<UserSummary> existingUsers = userPort.listByIds(memberIds);
        Set<Long> existingIds = existingUsers.stream()
                .map(UserSummary::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Long> missingIds = memberIds.stream()
                .filter(memberId -> !existingIds.contains(memberId))
                .toList();
        if (!missingIds.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "用户不存在或已注销: " + missingIds);
        }
        // 判断是否是互关用户 -> 不是互关用户则返回错误; Feign 守卫在事务外, 事务里只做纯写
        if (!followPort.interrelated(new FollowPort.InterrelatedQuery(userId, memberIds))) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是互关用户");
        }
        memberIds.add(userId);
        Conversation conversation = new Conversation();
        conversation.setId(IdWorker.getIdStr());
        conversation.setType(createConversationDTO.getType());
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setLastSeq(0L);
        conversation.setName(createConversationDTO.getName());
        conversation.setCreatedBy(userId);
        conversation.setAvatar(createConversationDTO.getAvatar());
        conversation.setBanned(false);
        conversation.setAnnouncement(null);
        conversation.setAnnouncementVersion(0);
        LocalDateTime now = LocalDateTime.now();
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        List<ConversationMember> members = new ArrayList<>();
        for (Long memberId : memberIds) {
            members.add(ConversationMember.newRow(memberId, conversation.getId(), now));
        }
        transactionTemplate.executeWithoutResult(tx -> {
            conversationMapper.insert(conversation);
            for (ConversationMember member : members) {
                conversationMemberMapper.insert(member);
            }
        });
        ConversationVO conversationVO = new ConversationVO();
        conversationVO.setId(conversation.getId());
        conversationVO.setType(createConversationDTO.getType());
        conversationVO.setMemberIds(memberIds);
        conversationVO.setName(createConversationDTO.getName());
        log.info("Conversation created conversationId={} type={} creatorId={} memberCount={}",
                conversation.getId(), createConversationDTO.getType(), userId, memberIds.size());
        return conversationVO;
    }

    public Optional<Conversation> getById(String conversationId) {
        return Optional.ofNullable(conversationMapper.selectById(conversationId));
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            // 未登录必须显式拒绝, 返回 null 会在私聊拆箱 NPE 或插出 user_id=null 的成员行
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }

        return Long.parseLong(userId);
    }

    public String updateConversationAvatar(String conversationId, MultipartFile avatar) {
        Long userId = getUserId();
        Conversation conversation = getConversation(conversationId);
        if (conversation.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能修改头像");
        }

        if (!conversation.getCreatedBy().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }

        // 上传文件到对象存储; 失败必须报错, 不能让空头像落库
        String avatarUrl;
        try {
            byte[] bytes = avatar.getBytes();
            avatarUrl = storageUtil.upload(bytes, avatar.getOriginalFilename(), userId, BIZ_PREFIX);
        } catch (IOException e) {
            log.error("上传文件到对象存储失败，avatar: {}", avatar.getOriginalFilename(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "头像上传失败, 请稍后重试");
        }

        // 定向更新只写头像, 避免整行回写把并发推进的 last_seq 等字段盖回旧值
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getAvatar, avatarUrl)
                .set(Conversation::getUpdatedAt, LocalDateTime.now()));
        return avatarUrl;
    }

    public String updateConversationName(String conversationId, String name) {
        Long userId = getUserId();
        Conversation conversation = getConversation(conversationId);
        if (conversation.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能修改名称");
        }

        if (!conversation.getCreatedBy().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }

        requireGroupName(name);
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getName, name)
                .set(Conversation::getUpdatedAt, LocalDateTime.now()));
        return name;
    }

    /**
     * 发布/更新群公告：仅群主（创建人），版本由 SQL 原子自增；经 WebSocket + SSE 门铃通知成员。
     * clientReqId 只参与幂等键: 双击/超时重发回放同一结果, 不同提交互不影响。
     */
    @Idempotent(key = "'{announcement-publish}:' + #userId + ':' + #conversationId + ':' + #clientReqId",
            policy = DuplicatePolicy.REPLAY)
    public AnnouncementPublishVO publishAnnouncement(String conversationId, String text, String clientReqId) {
        Long userId = getUserId();
        Conversation doc = getConversation(conversationId);
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

        // 版本自增和回读放在同一个事务里。更新会锁住这一行，另一个发布要等这次读完才继续。
        Integer nextVersion = transactionTemplate.execute(status -> {
            int updated = conversationMapper.publishAnnouncement(conversationId, t, LocalDateTime.now());
            if (updated != 1) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "会话不存在或已解散");
            }
            return conversationMapper.selectById(conversationId).getAnnouncementVersion();
        });
        int next = nextVersion;

        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("type", "ANNOUNCEMENT_UPDATED");
        ws.put("conversationId", conversationId);
        ws.put("version", next);
        String json = JsonUtil.toJson(ws);
        for (Long memberId : conversationMemberMapper.selectActiveUserIds(conversationId)) {
            if (!memberId.equals(userId)) {
                pushFanoutPublisher.pushWs(memberId, json);
                pushFanoutPublisher.pushSseSignal(memberId, AbstractConversationNotifyStrategy.CHAT_DIRTY_SIGNAL);
            }
        }
        return new AnnouncementPublishVO(conversationId, next, t);
    }

    public String deleteConversation(String conversationId) {
        Long userId = getUserId();
        Conversation conversation = getConversation(conversationId);
        List<Long> memberIds = conversationMemberMapper.selectActiveUserIds(conversationId);
        if (!memberIds.contains(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是会话成员");
        }

        boolean dissolved = false;
        if (conversation.getType() == ConversationTypeEnum.GROUP) {
            // 判断是否是创建人 -> 解散群
            if (conversation.getCreatedBy().equals(userId)) {
                dissolved = true;
                List<Long> others = transactionTemplate.execute(tx -> {
                    requireActiveConversation(conversationMapper.lockById(conversationId));
                    List<Long> recipients = conversationMemberMapper.selectActiveUserIds(conversationId).stream()
                            .filter(memberId -> !memberId.equals(userId)).toList();
                    conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                            .eq(Conversation::getId, conversationId)
                            .set(Conversation::getStatus, ConversationStatus.CLOSED)
                            .set(Conversation::getUpdatedAt, LocalDateTime.now()));
                    markConversationDeleted(conversationId);
                    return recipients;
                });
                // 通知仍在群里的成员。已经退出的人不再收到解散消息。
                if (!others.isEmpty()) {
                    GroupDeletedMessage message = new GroupDeletedMessage(conversationId, others);
                    try {
                        rocketMQTemplate.syncSend(GroupDeletedMessage.TOPIC, message);
                    } catch (RuntimeException ex) {
                        log.warn("群解散推送失败 conversationId={}", conversationId, ex);
                    }
                }
            } else {
                // 非创建人 -> 退出群
                markMembersDeleted(conversationId, List.of(userId));
            }
        } else if (conversation.getType() == ConversationTypeEnum.SINGLE) {
            privateConversationService.hidePrivateInbox(conversationId, userId);
        }
        log.info("Conversation deleted conversationId={} type={} operatorId={} dissolved={}",
                conversationId, conversation.getType(), userId, dissolved);
        return "删除成功";
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeConversationMember(String conversationId, List<Long> removeUserIds) {
        Conversation conversation = getConversation(conversationId);
        if (conversation.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能移除成员");
        }

        if (!conversation.getCreatedBy().equals(getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }

        if (removeUserIds == null || removeUserIds.isEmpty()) {
            return;
        }
        // 群主只有解散这条路; 放行自踢会造出"已退群但保留 createdBy 撤回权"的群主
        if (removeUserIds.contains(conversation.getCreatedBy())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "群主不能被移除, 退出请解散群");
        }
        markMembersDeleted(conversationId, removeUserIds);
    }

    Conversation getConversation(String conversationId) {
        Optional<Conversation> opt = getById(conversationId);
        if (opt.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }

        return requireActiveConversation(opt.get());
    }

    private Conversation requireActiveConversation(Conversation conversation) {
        return requireActiveConversation(conversation, "会话已关闭");
    }

    /** closedMessage 供调用方按会话类型给出更准确的提示, 如群路径报「群已解散」。 */
    Conversation requireActiveConversation(Conversation conversation, String closedMessage) {
        if (conversation == null || conversation.getStatus() == ConversationStatus.CLOSED) {
            throw new BizException(ErrorCode.STATE_CONFLICT, closedMessage);
        }
        return conversation;
    }

    public List<ConversationMembersVO> getConversationMembers(String conversationId) {
        Conversation conversation = getConversation(conversationId);
        if (conversation.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能查询成员");
        }

        List<Long> memberIds = conversationMemberMapper.selectActiveUserIds(conversationId);
        if (!memberIds.contains(getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群的成员");
        }
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
                    user.getNickname(), storageUtil.signGetUrl(user.getAvatarUrl(), MEMBER_LIST_AVATAR_EXPIRE_SECONDS),
                    userId.equals(conversation.getCreatedBy())));
        }
        return conversationMembersVOs;
    }

    public void banConversation(String conversationId, Boolean banned) {
        Conversation conversation = getConversation(conversationId);
        if (conversation.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能开启全员禁言");
        }

        if (!conversation.getCreatedBy().equals(getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }

        // 开启或关闭全员禁言; 定向更新, 不整行回写
        conversationMapper.update(null, new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .set(Conversation::getBanned, banned)
                .set(Conversation::getUpdatedAt, LocalDateTime.now()));
        List<Long> memberIds = conversationMemberMapper.selectActiveUserIds(conversationId).stream()
                .filter(memberId -> !memberId.equals(getUserId()))
                .toList();
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
        Conversation conversation = getConversation(conversationId);
        if (conversation.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊没有公告");
        }

        if (!conversationMemberMapper.selectActiveUserIds(conversationId).contains(getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群的成员");
        }

        if (conversation.getAnnouncement() == null) {
            return new AnnouncementPublishVO(conversationId, 0, null);
        }

        return new AnnouncementPublishVO(conversationId, conversation.getAnnouncementVersion(),
                conversation.getAnnouncement());
    }

    public void banConversationMember(Long userId, String conversationId, Boolean banned) {
        Conversation conversation = getConversation(conversationId);
        Long operatorId = getUserId();
        List<Long> memberIds = conversationMemberMapper.selectActiveUserIds(conversationId);
        if (!memberIds.contains(operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群的成员");
        }

        if (conversation.getType() != ConversationTypeEnum.GROUP) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能禁言成员");
        }
        if (!operatorId.equals(conversation.getCreatedBy())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是创建人");
        }
        if (banned == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "banned 不能为空");
        }
        if (userId.equals(conversation.getCreatedBy())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "不能禁言群主");
        }
        if (!memberIds.contains(userId)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "用户不在群中");
        }

        int updated = conversationMemberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId)
                .eq(ConversationMember::getDeleted, false)
                .set(ConversationMember::getBanned, banned)
                .set(ConversationMember::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "用户不在群中");
        }
        Map<String, Object> data = new HashMap<>(2);
        data.put("banned", banned);
        data.put("type", "member");
        data.put("conversationId", conversationId);
        String json = JsonUtil.toJson(data);
        pushFanoutPublisher.pushWs(userId, json);
    }

    private static void requireGroupName(String name) {
        if (name == null || name.isBlank() || name.length() > Conversation.NAME_MAX_LENGTH) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, Conversation.invalidNameMessage());
        }
    }

    private void markConversationDeleted(String conversationId) {
        conversationMemberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .set(ConversationMember::getDeleted, true)
                .set(ConversationMember::getUpdatedAt, LocalDateTime.now()));
    }

    private void markMembersDeleted(String conversationId, List<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        conversationMemberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .in(ConversationMember::getUserId, userIds)
                .set(ConversationMember::getDeleted, true)
                .set(ConversationMember::getUpdatedAt, LocalDateTime.now()));
    }
}

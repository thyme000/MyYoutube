package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationInviteMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.InviteStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationInvite;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationInviteVO;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.content.api.NotificationPort;
import cn.diaojiahong.myyoutube.content.api.enums.NotificationTypeEnum;
import cn.diaojiahong.myyoutube.content.api.enums.RefTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationInviteService {

    private static final String INVITE_EVENT = "CONVERSATION_INVITE";

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper conversationMemberMapper;
    private final ConversationInviteMapper conversationInviteMapper;
    private final ConversationService conversationService;
    private final FollowPort followPort;
    private final UserPort userPort;
    private final PushFanoutPublisher pushFanoutPublisher;
    private final NotificationPort notificationPort;
    private final TransactionTemplate transactionTemplate;

    public void inviteConversationMember(String conversationId, Long invitedUserId) {
        Conversation conversation = conversationService.getConversation(conversationId);
        Long userId = getUserId();
        assertGroupInvite(conversation, userId, invitedUserId);

        if (isActiveMember(conversationId, invitedUserId)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "用户已在群中");
        }

        ConversationInvite existing = findInvite(conversationId, invitedUserId);
        if (existing != null && existing.getStatus() == InviteStatusEnum.PENDING) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "已邀请，等待对方处理");
        }

        LocalDateTime now = LocalDateTime.now();
        ConversationInvite invite;
        if (existing == null) {
            invite = new ConversationInvite();
            invite.setId(IdWorker.getIdStr());
            invite.setConversationId(conversationId);
            invite.setInviterId(userId);
            invite.setInviteeId(invitedUserId);
            invite.setStatus(InviteStatusEnum.PENDING);
            invite.setCreatedAt(now);
            invite.setUpdatedAt(now);
            try {
                conversationInviteMapper.insert(invite);
            } catch (DuplicateKeyException duplicate) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "已邀请，等待对方处理");
            }
        } else {
            invite = existing;
            conversationInviteMapper.update(null, new LambdaUpdateWrapper<ConversationInvite>()
                    .eq(ConversationInvite::getId, existing.getId())
                    .set(ConversationInvite::getInviterId, userId)
                    .set(ConversationInvite::getStatus, InviteStatusEnum.PENDING)
                    .set(ConversationInvite::getCreatedAt, now)
                    .set(ConversationInvite::getUpdatedAt, now));
            invite.setInviterId(userId);
        }
        notifyInvite(conversation, invite);
    }

    /**
     * 我收到的群邀请，按邀请时间倒序。status 为空返回全部状态，非空只返回该状态；
     * 群已解散但尚未处理的邀请同样返回，接受时再由会话状态校验拦下。
     */
    public List<ConversationInviteVO> listInvites(String statusCode) {
        Long userId = getUserId();
        InviteStatusEnum status = parseInviteStatus(statusCode);
        LambdaQueryWrapper<ConversationInvite> query = new LambdaQueryWrapper<ConversationInvite>()
                .eq(ConversationInvite::getInviteeId, userId)
                .orderByDesc(ConversationInvite::getCreatedAt);
        if (status != null) {
            query.eq(ConversationInvite::getStatus, status);
        }
        List<ConversationInvite> invites = conversationInviteMapper.selectList(query);
        if (invites.isEmpty()) {
            return List.of();
        }

        List<String> conversationIds = invites.stream()
                .map(ConversationInvite::getConversationId)
                .distinct()
                .toList();
        Map<String, Conversation> conversations = conversationMapper.selectList(
                        new LambdaQueryWrapper<Conversation>().in(Conversation::getId, conversationIds))
                .stream()
                .collect(Collectors.toMap(Conversation::getId, conversation -> conversation));
        List<Long> inviterIds = invites.stream()
                .map(ConversationInvite::getInviterId)
                .distinct()
                .toList();
        Map<Long, UserSummary> inviters = userPort.listByIds(inviterIds).stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(UserSummary::getId, user -> user, (first, second) -> first));

        List<ConversationInviteVO> result = new ArrayList<>();
        for (ConversationInvite invite : invites) {
            Conversation conversation = conversations.get(invite.getConversationId());
            UserSummary inviter = inviters.get(invite.getInviterId());
            ConversationInviteVO vo = new ConversationInviteVO();
            vo.setId(invite.getId());
            vo.setConversationId(invite.getConversationId());
            vo.setConversationName(conversation == null ? null : conversation.getName());
            vo.setInviterId(invite.getInviterId());
            vo.setInviterNickname(inviter == null ? null : inviter.getNickname());
            vo.setStatus(invite.getStatus());
            vo.setCreatedAt(invite.getCreatedAt());
            result.add(vo);
        }
        return result;
    }

    /** 邀请状态筛选项：空码表示不筛；非法码直接报参数错误，不放进查询条件。 */
    private InviteStatusEnum parseInviteStatus(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return InviteStatusEnum.valueOf(code);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "无效的邀请状态: " + code);
        }
    }

    public void acceptInvite(String inviteId) {
        Long userId = getUserId();
        ConversationInvite invite = requirePendingInvite(inviteId, userId);
        // 不走 getConversation: 它对已关闭会话报笼统的「会话已关闭」, 这里要说清是群已解散
        Conversation conversation = conversationService.getById(invite.getConversationId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "会话不存在"));
        if (conversation.getType() != ConversationTypeEnum.GROUP) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能邀请成员");
        }
        conversationService.requireActiveConversation(conversation, "群已解散");
        if (!followPort.interrelated(new FollowPort.InterrelatedQuery(invite.getInviterId(), List.of(userId)))) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是互关用户");
        }

        transactionTemplate.executeWithoutResult(status -> {
            Conversation current = conversationService.requireActiveConversation(
                    conversationMapper.lockById(invite.getConversationId()), "群已解散");
            int updated = markInvite(inviteId, userId, InviteStatusEnum.ACCEPTED);
            if (updated != 1) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "邀请已处理");
            }
            long lastSeq = current.getLastSeq() == null ? 0L : current.getLastSeq();
            joinGroup(invite.getConversationId(), userId, lastSeq, LocalDateTime.now());
        });
    }

    public void rejectInvite(String inviteId) {
        Long userId = getUserId();
        requirePendingInvite(inviteId, userId);
        int updated = markInvite(inviteId, userId, InviteStatusEnum.REJECTED);
        if (updated != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "邀请已处理");
        }
    }

    private void assertGroupInvite(Conversation conversation, Long inviterId, Long inviteeId) {
        if (inviteeId.equals(inviterId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不能邀请自己");
        }
        UserSummary user = userPort.getUser(inviteeId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (conversation.getType() == ConversationTypeEnum.SINGLE) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "私聊不能邀请成员");
        }
        if (!conversationMemberMapper.selectActiveUserIds(conversation.getId()).contains(inviterId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群的成员");
        }
        if (!followPort.interrelated(new FollowPort.InterrelatedQuery(inviterId, List.of(inviteeId)))) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是互关用户,无法邀请对方");
        }
    }

    private boolean isActiveMember(String conversationId, Long userId) {
        return conversationMemberMapper.selectActiveUserIds(conversationId).contains(userId);
    }

    private ConversationInvite findInvite(String conversationId, Long inviteeId) {
        return conversationInviteMapper.selectOne(new LambdaQueryWrapper<ConversationInvite>()
                .eq(ConversationInvite::getConversationId, conversationId)
                .eq(ConversationInvite::getInviteeId, inviteeId));
    }

    private ConversationInvite requirePendingInvite(String inviteId, Long inviteeId) {
        ConversationInvite invite = conversationInviteMapper.selectById(inviteId);
        if (invite == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "邀请不存在");
        }
        if (!inviteeId.equals(invite.getInviteeId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是被邀请人");
        }
        if (invite.getStatus() != InviteStatusEnum.PENDING) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "邀请已处理");
        }
        return invite;
    }

    private int markInvite(String inviteId, Long inviteeId, InviteStatusEnum status) {
        return conversationInviteMapper.update(null, new LambdaUpdateWrapper<ConversationInvite>()
                .eq(ConversationInvite::getId, inviteId)
                .eq(ConversationInvite::getInviteeId, inviteeId)
                .eq(ConversationInvite::getStatus, InviteStatusEnum.PENDING)
                .set(ConversationInvite::getStatus, status)
                .set(ConversationInvite::getUpdatedAt, LocalDateTime.now()));
    }

    private void joinGroup(String conversationId, Long userId, long lastSeq, LocalDateTime now) {
        ConversationMember existing = conversationMemberMapper.selectOne(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .eq(ConversationMember::getUserId, userId));
        if (existing != null && !Boolean.TRUE.equals(existing.getDeleted())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "用户已在群中");
        }
        if (existing == null) {
            ConversationMember member = ConversationMember.newRow(userId, conversationId, now);
            hideHistory(member, lastSeq);
            try {
                conversationMemberMapper.insert(member);
            } catch (DuplicateKeyException duplicate) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "用户已在群中");
            }
            return;
        }
        conversationMemberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .eq(ConversationMember::getId, existing.getId())
                .set(ConversationMember::getDeleted, false)
                .set(ConversationMember::getCreatedAt, now)
                .set(ConversationMember::getLastReadSeq, lastSeq)
                .set(ConversationMember::getVisibleAfterSeq, lastSeq)
                .set(ConversationMember::getBanned, false)
                .set(ConversationMember::getLastMessageId, null)
                .set(ConversationMember::getLastMessagePreview, null)
                .set(ConversationMember::getLastMessageSenderNickname, null)
                .set(ConversationMember::getLastMessageAt, null)
                .set(ConversationMember::getIsLastMessageRecall, false)
                .set(ConversationMember::getUpdatedAt, now));
    }

    private static void hideHistory(ConversationMember member, long lastSeq) {
        member.setLastReadSeq(lastSeq);
        member.setVisibleAfterSeq(lastSeq);
    }

    private void notifyInvite(Conversation conversation, ConversationInvite invite) {
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("type", INVITE_EVENT);
        ws.put("inviteId", invite.getId());
        ws.put("conversationId", conversation.getId());
        ws.put("conversationName", conversation.getName());
        try {
            pushFanoutPublisher.pushWs(invite.getInviteeId(), JsonUtil.toJson(ws));
            notificationPort.createNotification(new NotificationPort.NotificationCreate(
                    invite.getInviteeId(),
                    invite.getInviterId(),
                    NotificationTypeEnum.CHAT_NOTICE.getCode(),
                    inviteText(conversation),
                    RefTypeEnum.CHAT.getCode(),
                    null,
                    conversation.getId()));
        } catch (RuntimeException ex) {
            log.warn("群邀请通知发送失败 inviteId={} inviteeId={}", invite.getId(), invite.getInviteeId(), ex);
        }
    }

    private static String inviteText(Conversation conversation) {
        String name = conversation.getName();
        if (name == null || name.isBlank()) {
            name = conversation.getId();
        }
        return "邀请你加入群:" + name;
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }

        return Long.parseLong(userId);
    }
}

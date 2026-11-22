package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.domain.dto.ConversationListCursor;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.domain.dto.ConversationUnreadRow;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationUnreadItemVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationUnreadVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.UserConversationVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ValidUserConversationVO;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.page.CursorPageQuery;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemberService {

    private static final long SENDABLE_LIST_AVATAR_EXPIRE_SECONDS = 5 * 60;
    private static final long INBOX_AVATAR_EXPIRE_SECONDS = 3600 * 10;
    private static final String RECALLED_MESSAGE_PREVIEW = "撤回了一条消息";

    private final ConversationMemberMapper conversationMemberMapper;
    private final ConversationMapper conversationMapper;
    private final PrivateConversationService privateConversationService;
    private final S3StorageUtil storageUtil;
    private final UserPort userPort;
    private final UserBlockPort blocks;

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }

        return Long.parseLong(userId);
    }

    public void setMute(String conversationId, boolean mute) {
        int updated = conversationMemberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, getUserId())
                .eq(ConversationMember::getDeleted, false)
                .set(ConversationMember::getMute, mute)
                .set(ConversationMember::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是会话成员");
        }
    }

    public void setPin(String conversationId, boolean pin) {
        int updated = conversationMemberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, getUserId())
                .eq(ConversationMember::getDeleted, false)
                .set(ConversationMember::getPin, pin)
                .set(ConversationMember::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "不是会话成员");
        }
    }

    public CursorPage<UserConversationVO, String> listInbox(String cursor) {
        long userId = getUserId();
        MembershipPage page = loadPage(userId, cursor, false);

        Map<Long, UserSummary> peerById = loadPeerSummaries(peerIdsOf(page, userId));
        Set<Long> blockedUsers = page.rows().isEmpty() ? Set.of() : Set.copyOf(blocks.blockedAuthors(userId));
        List<UserConversationVO> items = new ArrayList<>();
        for (ConversationMember member : page.rows()) {
            Conversation conversation = page.conversations().get(member.getConversationId());
            if (conversation == null) {
                continue;
            }
            UserSummary peer = null;
            boolean blockedByMe = false;
            if (conversation.getType() == ConversationTypeEnum.SINGLE) {
                // 私聊对端按 user_a/user_b 反推: 对方可能还没打开过, 成员表里未必有他的行
                Long peerId = conversation.peerOf(userId);
                peer = peerById.get(peerId);
                blockedByMe = blockedUsers.contains(peerId);
            }
            items.add(toInbox(member, conversation, peer, blockedByMe));
        }
        return new CursorPage<>(items, page.nextCursor(), page.hasMore());
    }

    public UserConversationVO findPrivateInbox(Long peerId) {
        Long userId = getUserId();
        if (peerId <= 0 || peerId.equals(userId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "对方用户 ID 无效");
        }
        Conversation conversation = privateConversationService.findPrivateChat(userId, peerId);
        // 自己没有成员行时返回空: 空私聊只有打开者一条成员行, 另一方的只读查询看不到
        return conversation == null ? null : privateInbox(conversation, userId, peerId);
    }

    public UserConversationVO openPrivate(Long peerId) {
        Long userId = getUserId();
        if (peerId <= 0 || peerId.equals(userId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "对方用户 ID 无效");
        }
        Conversation conversation = privateConversationService.openOrReuse(userId, peerId);
        return privateInbox(conversation, userId, peerId);
    }

    public UserConversationVO privateInbox(Conversation conversation, Long peerId) {
        return privateInbox(conversation, getUserId(), peerId);
    }

    private UserConversationVO privateInbox(Conversation conversation, Long userId, Long peerId) {
        ConversationMember mine = conversationMemberMapper.selectOne(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversation.getId())
                        .eq(ConversationMember::getUserId, userId)
                        .eq(ConversationMember::getDeleted, false)
                        .eq(ConversationMember::getInboxVisible, true));
        if (mine == null) {
            return null;
        }

        return toInbox(mine, conversation, userPort.getUser(peerId),
                blocks.blockedAuthors(userId).contains(peerId));
    }

    /**
     * 聊天未读。totalUnread 是本次清单里未读数之和。
     * 从收件箱移除的私聊已把已读水位推到当时的最新序号，未读为 0；新消息会让它重新出现并计入总数。
     * 当前打开且仍是成员的会话不在清单里时补一条明细和 lastSeq，已在清单里的不重复。
     * 不是成员的 activeConversationId 忽略，不让一次查询因为当前会话失效而整单失败。
     */
    public ConversationUnreadVO listUnread(String activeConversationId) {
        long userId = getUserId();
        String activeId = activeConversationId == null ? "" : activeConversationId.trim();
        List<ConversationUnreadItemVO> conversations = new ArrayList<>();
        boolean activeIncluded = false;
        long totalUnread = 0L;
        for (ConversationUnreadRow row : conversationMemberMapper.listUnreadRows(userId)) {
            ConversationUnreadItemVO item = toUnreadItem(row);
            conversations.add(item);
            totalUnread += item.getUnreadCount();
            if (row.getConversationId().equals(activeId)) {
                activeIncluded = true;
            }
        }
        if (!activeId.isEmpty() && !activeIncluded) {
            ConversationUnreadRow active = conversationMemberMapper.findMemberUnreadRow(userId, activeId);
            if (active != null) {
                ConversationUnreadItemVO item = toUnreadItem(active);
                conversations.add(item);
                totalUnread += item.getUnreadCount();
            }
        }

        ConversationUnreadVO vo = new ConversationUnreadVO();
        vo.setTotalUnread(totalUnread);
        vo.setConversations(conversations);
        return vo;
    }

    public void readAnnouncement(String conversationId) {
        Long userId = getUserId();
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }

        ConversationMember membership = conversationMemberMapper.selectOne(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, conversationId)
                        .eq(ConversationMember::getUserId, userId)
                        .eq(ConversationMember::getDeleted, false));
        if (membership == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "用户不是群成员");
        }
        if (conversation.getAnnouncement() == null) {
            return;
        }

        conversationMemberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId)
                .set(ConversationMember::getLastSeenAnnouncementVersion, conversation.getAnnouncementVersion())
                .set(ConversationMember::getUpdatedAt, LocalDateTime.now()));
    }

    public CursorPage<ValidUserConversationVO, String> listSendable(String cursor) {
        long userId = getUserId();
        MembershipPage page = loadPage(userId, cursor, true);
        List<ValidUserConversationVO> items = new ArrayList<>();
        for (ConversationMember member : page.rows()) {
            Conversation conversation = page.conversations().get(member.getConversationId());
            if (conversation == null) {
                continue;
            }
            ValidUserConversationVO vo = new ValidUserConversationVO();
            vo.setConversationId(member.getConversationId());
            vo.setConversationType(conversation.getType());
            if (conversation.getType() == ConversationTypeEnum.SINGLE) {
                // 私聊对端按 user_a/user_b 反推: 对方可能还没打开过, 成员表里未必有他的行
                Long peerId = conversation.peerOf(userId);
                UserSummary peer = peerId == null ? null : loadPeerSummary(peerId);
                if (peer != null) {
                    vo.setConversationName(peer.getNickname());
                    vo.setConversationAvatar(storageUtil.signGetUrl(peer.getAvatarUrl(), SENDABLE_LIST_AVATAR_EXPIRE_SECONDS));
                }
            } else {
                vo.setConversationName(conversation.getName());
                vo.setConversationAvatar(storageUtil.signGetUrl(conversation.getAvatar(), SENDABLE_LIST_AVATAR_EXPIRE_SECONDS));
            }
            items.add(vo);
        }
        return new CursorPage<>(items, page.nextCursor(), page.hasMore());
    }

    /** 一页私聊行的对端 id, 按 user_a/user_b 反推后去重。 */
    private List<Long> peerIdsOf(MembershipPage page, long userId) {
        return page.rows().stream()
                .map(member -> page.conversations().get(member.getConversationId()))
                .filter(conversation -> conversation != null
                        && conversation.getType() == ConversationTypeEnum.SINGLE)
                .map(conversation -> conversation.peerOf(userId))
                .distinct()
                .toList();
    }

    private UserSummary loadPeerSummary(Long peerId) {
        List<UserSummary> found = userPort.listByIds(List.of(peerId));
        return found.isEmpty() ? null : found.get(0);
    }

    private Map<Long, UserSummary> loadPeerSummaries(Collection<Long> peerIds) {
        List<Long> distinctIds = peerIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return userPort.listByIds(distinctIds).stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(UserSummary::getId, Function.identity(), (first, second) -> first));
    }

    private MembershipPage loadPage(long userId, String cursor, boolean sendableOnly) {
        ConversationListCursor position = ConversationListCursor.decode(cursor, userId, sendableOnly);
        int pageSize = CursorPageQuery.PAGE_SIZE;
        List<ConversationMember> fetched = conversationMemberMapper.listByActivity(
                userId, position, sendableOnly, CursorPage.fetchSize(pageSize));
        CursorPage<ConversationMember, String> sliced = CursorPage.slice(
                fetched, pageSize, row -> encode(userId, sendableOnly, row));
        return new MembershipPage(
                sliced.items(), loadConversations(sliced.items()), sliced.nextCursor(), sliced.hasMore());
    }

    private String encode(long userId, boolean sendableOnly, ConversationMember row) {
        int pin = Boolean.TRUE.equals(row.getPin()) ? 1 : 0;
        return new ConversationListCursor(userId, pin, row.getActivityAt(), row.getId(), sendableOnly).encode();
    }

    private Map<String, Conversation> loadConversations(List<ConversationMember> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<String> ids = rows.stream().map(ConversationMember::getConversationId).distinct().toList();
        List<Conversation> found = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>().in(Conversation::getId, ids));
        return found.stream().collect(Collectors.toMap(Conversation::getId, Function.identity()));
    }

    private UserConversationVO toInbox(ConversationMember member, Conversation conversation,
                                       UserSummary peer, boolean blockedByMe) {
        boolean single = conversation.getType() == ConversationTypeEnum.SINGLE;
        UserConversationVO vo = new UserConversationVO();
        vo.setConversationId(member.getConversationId());
        if (single) {
            vo.setBlockedByMe(blockedByMe);
            // 对端可能已注销: 名称头像留空即可, 不能让整个收件箱 500
            if (peer != null) {
                vo.setConversationAvatar(storageUtil.signGetUrl(peer.getAvatarUrl(), INBOX_AVATAR_EXPIRE_SECONDS));
                vo.setConversationName(peer.getNickname());
            }
        } else {
            vo.setConversationAvatar(storageUtil.signGetUrl(conversation.getAvatar(), INBOX_AVATAR_EXPIRE_SECONDS));
            vo.setConversationName(conversation.getName() == null ? conversation.getId() : conversation.getName());
        }
        vo.setConversationType(conversation.getType());
        vo.setStatus(conversation.getStatus());
        vo.setLastMessageTime(member.getLastMessageAt());
        vo.setLastMessageContent(Boolean.TRUE.equals(member.getIsLastMessageRecall())
                ? RECALLED_MESSAGE_PREVIEW
                : member.getLastMessagePreview());
        vo.setLastMessageSenderNickname(single ? null : member.getLastMessageSenderNickname());
        vo.setIsLastMessageRecall(member.getIsLastMessageRecall());
        vo.setBanned(member.getBanned());
        vo.setConversationBanned(conversation.getBanned());
        vo.setPin(member.getPin());
        vo.setMute(member.getMute());
        vo.setLastReadSeq(member.getLastReadSeq());
        vo.setUnreadCount(unreadCount(conversation, member));
        vo.setCreatedBy(conversation.getCreatedBy());
        vo.setLastSeenAnnouncementVersion(member.getLastSeenAnnouncementVersion());
        vo.setLastAnnouncementVersion(conversation.getAnnouncementVersion());
        return vo;
    }

    private record MembershipPage(
            List<ConversationMember> rows,
            Map<String, Conversation> conversations,
            String nextCursor,
            boolean hasMore) {
    }

    private static long unreadCount(Conversation conversation, ConversationMember member) {
        long lastSeq = conversation.getLastSeq() == null ? 0L : conversation.getLastSeq();
        return unreadBetween(lastSeq, member.getLastReadSeq());
    }

    private static ConversationUnreadItemVO toUnreadItem(ConversationUnreadRow row) {
        long lastSeq = row.getLastSeq() == null ? 0L : row.getLastSeq();
        ConversationUnreadItemVO vo = new ConversationUnreadItemVO();
        vo.setConversationId(row.getConversationId());
        vo.setLastSeq(lastSeq);
        vo.setUnreadCount(unreadBetween(lastSeq, row.getLastReadSeq()));
        return vo;
    }

    private static long unreadBetween(long lastSeq, Long lastReadSeq) {
        long readSeq = lastReadSeq == null ? 0L : lastReadSeq;
        long unread = lastSeq - readSeq;
        if (unread < 0L) {
            unread = 0L;
        }
        return unread;
    }
}

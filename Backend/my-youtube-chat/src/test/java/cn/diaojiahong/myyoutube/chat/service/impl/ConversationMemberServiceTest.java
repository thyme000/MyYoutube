package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.domain.dto.ConversationUnreadRow;
import cn.diaojiahong.myyoutube.chat.domain.dto.ConversationListCursor;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationUnreadItemVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationUnreadVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.UserConversationVO;
import cn.diaojiahong.myyoutube.chat.domain.vo.ValidUserConversationVO;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemberServiceTest {

    @Test
    void skipsMissingConversationAndKeepsCursorOnTheLastMembership() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        ConversationMemberService service = new ConversationMemberService(
                memberships, conversations, mock(PrivateConversationService.class),
                mock(S3StorageUtil.class), mock(UserPort.class), mock(UserBlockPort.class));
        List<ConversationMember> rows = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 11, 18, 12, 0);
        for (int i = 0; i < 11; i++) {
            ConversationMember member = new ConversationMember();
            member.setId("m" + i);
            member.setConversationId("c" + i);
            member.setPin(i == 0);
            member.setActivityAt(start.minusMinutes(i));
            rows.add(member);
        }
        when(memberships.listByActivity(eq(9001L), isNull(), eq(true), eq(11)))
                .thenReturn(rows);
        Conversation kept = new Conversation();
        kept.setId("c1");
        kept.setName("可发送群");
        when(conversations.selectList(any())).thenReturn(List.of(kept));

        UserContext.setUser("9001");
        try {
            CursorPage<ValidUserConversationVO, String> result = service.listSendable(null);
            assertEquals(List.of("c1"), result.items().stream().map(ValidUserConversationVO::getConversationId).toList());
            assertTrue(result.hasMore());
            ConversationListCursor cursor = ConversationListCursor.decode(result.nextCursor(), 9001L, true);
            assertEquals(0, cursor.pin());
            assertEquals(start.minusMinutes(9), cursor.activityAt());
            assertEquals("m9", cursor.rowId());
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void rejectsInboxCursorOnTheSendableList() {
        ConversationMemberService service = new ConversationMemberService(
                mock(ConversationMemberMapper.class),
                mock(ConversationMapper.class),
                mock(PrivateConversationService.class),
                mock(S3StorageUtil.class),
                mock(UserPort.class), mock(UserBlockPort.class));
        String inboxCursor = new ConversationListCursor(
                9001L, 1, LocalDateTime.of(2026, 11, 18, 12, 0), "m1", false).encode();
        UserContext.setUser("9001");
        try {
            assertThrows(BizException.class, () -> service.listSendable(inboxCursor));
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void unreadTotalIsTheSumOfTheReturnedConversations() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        ConversationMemberService service = service(memberships);
        List<ConversationUnreadRow> unread = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            unread.add(unreadRow("c" + i, 10L + i, 4L));
        }
        when(memberships.listUnreadRows(9001L)).thenReturn(unread);

        UserContext.setUser("9001");
        try {
            ConversationUnreadVO result = service.listUnread(null);
            assertEquals(2130L, result.getTotalUnread());
            assertEquals(60, result.getConversations().size());
            ConversationUnreadItemVO first = result.getConversations().get(0);
            assertEquals("c0", first.getConversationId());
            assertEquals(10L, first.getLastSeq());
            assertEquals(6L, first.getUnreadCount().longValue());
            verify(memberships, never()).findMemberUnreadRow(any(), any());
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void clearedConversationStaysOutUnlessItIsTheActiveOne() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        ConversationMemberService service = service(memberships);
        when(memberships.listUnreadRows(9001L)).thenReturn(List.of(unreadRow("still-unread", 8L, 3L)));
        when(memberships.findMemberUnreadRow(9001L, "cleared")).thenReturn(unreadRow("cleared", 12L, 12L));

        UserContext.setUser("9001");
        try {
            ConversationUnreadVO withoutActive = service.listUnread("  ");
            assertEquals(List.of("still-unread"), ids(withoutActive));
            assertEquals(5L, withoutActive.getTotalUnread());
            verify(memberships, never()).findMemberUnreadRow(eq(9001L), eq("cleared"));

            ConversationUnreadVO withActive = service.listUnread("cleared");
            assertEquals(List.of("still-unread", "cleared"), ids(withActive));
            ConversationUnreadItemVO cleared = withActive.getConversations().get(1);
            assertEquals(12L, cleared.getLastSeq());
            assertEquals(0L, cleared.getUnreadCount().longValue());
            assertEquals(5L, withActive.getTotalUnread());
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void hiddenActiveConversationIsCaughtUpAndLeavesTheTotalUnchanged() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        ConversationMemberService service = service(memberships);
        when(memberships.listUnreadRows(9001L)).thenReturn(List.of(unreadRow("visible", 9L, 4L)));
        when(memberships.findMemberUnreadRow(9001L, "hidden")).thenReturn(unreadRow("hidden", 10L, 10L));

        UserContext.setUser("9001");
        try {
            ConversationUnreadVO result = service.listUnread("hidden");
            assertEquals(List.of("visible", "hidden"), ids(result));
            ConversationUnreadItemVO hidden = result.getConversations().get(1);
            assertEquals(10L, hidden.getLastSeq());
            assertEquals(0L, hidden.getUnreadCount().longValue());
            assertEquals(5L, result.getTotalUnread());
            verify(memberships).findMemberUnreadRow(9001L, "hidden");
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void activeUnreadConversationAppearsOnce() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        ConversationMemberService service = service(memberships);
        when(memberships.listUnreadRows(9001L)).thenReturn(List.of(unreadRow("open", 20L, 11L)));

        UserContext.setUser("9001");
        try {
            ConversationUnreadVO result = service.listUnread("open");
            assertEquals(List.of("open"), ids(result));
            assertEquals(9L, result.getConversations().get(0).getUnreadCount().longValue());
            assertEquals(9L, result.getTotalUnread());
            verify(memberships, never()).findMemberUnreadRow(any(), any());
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void inaccessibleActiveConversationIsOmitted() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        ConversationMemberService service = service(memberships);
        when(memberships.listUnreadRows(9001L)).thenReturn(List.of());
        when(memberships.findMemberUnreadRow(9001L, "foreign")).thenReturn(null);

        UserContext.setUser("9001");
        try {
            ConversationUnreadVO result = service.listUnread("foreign");
            assertEquals(List.of(), result.getConversations());
            assertEquals(0L, result.getTotalUnread());
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void unreadAboveIntegerMaxStaysExactOnUnreadAndInbox() {
        long unread = 2_147_483_648L;
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        UserBlockPort blocks = mock(UserBlockPort.class);
        ConversationMemberService service = new ConversationMemberService(
                memberships, conversations, mock(PrivateConversationService.class),
                mock(S3StorageUtil.class), mock(UserPort.class), blocks);
        when(memberships.listUnreadRows(9001L)).thenReturn(List.of(unreadRow("wide", unread, 0L)));

        ConversationMember member = new ConversationMember();
        member.setId("m-wide");
        member.setConversationId("wide");
        member.setLastReadSeq(0L);
        member.setPin(false);
        member.setActivityAt(LocalDateTime.of(2026, 11, 22, 12, 0));
        when(memberships.listByActivity(eq(9001L), isNull(), eq(false), eq(11)))
                .thenReturn(List.of(member));
        Conversation conversation = new Conversation();
        conversation.setId("wide");
        conversation.setType(ConversationTypeEnum.GROUP);
        conversation.setName("大序号群");
        conversation.setLastSeq(unread);
        when(conversations.selectList(any())).thenReturn(List.of(conversation));
        when(blocks.blockedAuthors(9001L)).thenReturn(List.of());

        UserContext.setUser("9001");
        try {
            ConversationUnreadVO unreadResult = service.listUnread(null);
            ConversationUnreadItemVO item = unreadResult.getConversations().get(0);
            assertEquals("wide", item.getConversationId());
            assertEquals(unread, item.getLastSeq());
            assertEquals(unread, item.getUnreadCount().longValue());
            assertEquals(unread, unreadResult.getTotalUnread());

            UserConversationVO listed = service.listInbox(null).items().get(0);
            assertEquals("wide", listed.getConversationId());
            assertEquals(unread, listed.getUnreadCount().longValue());
        } finally {
            UserContext.removeUser();
        }
    }

    @Test
    void negativeReadGapClampsToZero() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        ConversationMemberService service = service(memberships);
        when(memberships.listUnreadRows(9001L)).thenReturn(List.of());
        when(memberships.findMemberUnreadRow(9001L, "rewound")).thenReturn(unreadRow("rewound", 4L, 9L));

        UserContext.setUser("9001");
        try {
            ConversationUnreadVO result = service.listUnread("rewound");
            ConversationUnreadItemVO item = result.getConversations().get(0);
            assertEquals(4L, item.getLastSeq());
            assertEquals(0L, item.getUnreadCount().longValue());
            assertEquals(0L, result.getTotalUnread());
        } finally {
            UserContext.removeUser();
        }
    }

    private static ConversationMemberService service(ConversationMemberMapper memberships) {
        return new ConversationMemberService(
                memberships, mock(ConversationMapper.class), mock(PrivateConversationService.class),
                mock(S3StorageUtil.class), mock(UserPort.class), mock(UserBlockPort.class));
    }

    private static ConversationUnreadRow unreadRow(String conversationId, long lastSeq, long lastReadSeq) {
        ConversationUnreadRow row = new ConversationUnreadRow();
        row.setConversationId(conversationId);
        row.setLastSeq(lastSeq);
        row.setLastReadSeq(lastReadSeq);
        return row;
    }

    private static List<String> ids(ConversationUnreadVO result) {
        return result.getConversations().stream().map(ConversationUnreadItemVO::getConversationId).toList();
    }
}

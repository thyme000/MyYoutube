package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.domain.dto.ConversationListCursor;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
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
}

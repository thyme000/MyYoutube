package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.InviteStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationInvite;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.domain.vo.ConversationInviteVO;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationInviteMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.NotificationPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationInviteTest {

    private static final Long INVITER = 11L;
    private static final Long INVITEE = 22L;

    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final ConversationMemberMapper members = mock(ConversationMemberMapper.class);
    private final ConversationInviteMapper invites = mock(ConversationInviteMapper.class);
    private final FollowPort followPort = mock(FollowPort.class);
    private final UserPort userPort = mock(UserPort.class);
    private final PushFanoutPublisher push = mock(PushFanoutPublisher.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final NotificationPort notifications = mock(NotificationPort.class);

    private final ConversationService conversationService = new ConversationService(
            conversations, members, mock(PrivateConversationService.class), followPort, userPort,
            mock(S3StorageUtil.class), mock(RocketMQTemplate.class),
            push, transactions);

    private final ConversationInviteService service = new ConversationInviteService(
            conversations, members, invites, conversationService,
            followPort, userPort, push, notifications, transactions);

    @BeforeAll
    static void registerInviteColumns() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConversationInvite.class);
        TableInfoHelper.initTableInfo(assistant, ConversationMember.class);
    }

    @BeforeEach
    void signIn() {
        UserContext.setUser(String.valueOf(INVITER));
        UserSummary invitee = new UserSummary();
        invitee.setId(INVITEE);
        when(userPort.getUser(INVITEE)).thenReturn(invitee);
        when(followPort.interrelated(any())).thenReturn(true);
        doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @AfterEach
    void signOut() {
        UserContext.removeUser();
    }

    @Test
    void inviteWritesAPendingRowAndDoesNotAddAMember() {
        when(conversations.selectById("group")).thenReturn(group(4L));
        when(members.selectActiveUserIds("group")).thenReturn(List.of(INVITER));
        when(invites.selectOne(any())).thenReturn(null);

        service.inviteConversationMember("group", INVITEE);

        ArgumentCaptor<ConversationInvite> saved = ArgumentCaptor.forClass(ConversationInvite.class);
        verify(invites).insert(saved.capture());
        assertEquals(InviteStatusEnum.PENDING, saved.getValue().getStatus());
        assertEquals(INVITEE, saved.getValue().getInviteeId());
        verify(members, never()).insert(any(ConversationMember.class));
        verify(notifications).createNotification(any());
        verify(push).pushWs(org.mockito.ArgumentMatchers.eq(INVITEE), any());
    }

    @Test
    void invitingAnActiveMemberConflicts() {
        when(conversations.selectById("group")).thenReturn(group(4L));
        when(members.selectActiveUserIds("group")).thenReturn(List.of(INVITER, INVITEE));

        BizException error = assertThrows(BizException.class,
                () -> service.inviteConversationMember("group", INVITEE));

        assertEquals("用户已在群中", error.getMessage());
        verify(invites, never()).insert(any(ConversationInvite.class));
        verify(members, never()).insert(any(ConversationMember.class));
    }

    @Test
    void acceptJoinsAtTheCurrentSequenceAndMarksTheInviteAccepted() {
        UserContext.setUser(String.valueOf(INVITEE));
        ConversationInvite pending = pendingInvite();
        when(invites.selectById("invite-1")).thenReturn(pending);
        when(invites.update(any(), any())).thenReturn(1);
        when(conversations.selectById("group")).thenReturn(group(9L));
        when(conversations.lockById("group")).thenReturn(group(9L));
        when(members.selectOne(any())).thenReturn(null);

        service.acceptInvite("invite-1");

        ArgumentCaptor<ConversationMember> joined = ArgumentCaptor.forClass(ConversationMember.class);
        verify(members).insert(joined.capture());
        assertEquals(9L, joined.getValue().getLastReadSeq());
        assertEquals(9L, joined.getValue().getVisibleAfterSeq());
        verify(invites).update(any(), any());
    }

    @Test
    void acceptingAnInviteAfterDissolutionDoesNotWriteMembership() {
        UserContext.setUser(String.valueOf(INVITEE));
        when(invites.selectById("invite-1")).thenReturn(pendingInvite());
        when(conversations.selectById("group")).thenReturn(group(9L));
        Conversation closed = group(9L);
        closed.setStatus(ConversationStatus.CLOSED);
        when(conversations.lockById("group")).thenReturn(closed);

        BizException error = assertThrows(BizException.class, () -> service.acceptInvite("invite-1"));

        assertEquals("群已解散", error.getMessage());
        verify(invites, never()).update(any(), any());
        verify(members, never()).insert(any(ConversationMember.class));
    }

    @Test
    void acceptingAnInviteWhoseGroupRowIsAlreadyClosedNamesTheDissolution() {
        UserContext.setUser(String.valueOf(INVITEE));
        when(invites.selectById("invite-1")).thenReturn(pendingInvite());
        Conversation closed = group(9L);
        closed.setStatus(ConversationStatus.CLOSED);
        when(conversations.selectById("group")).thenReturn(closed);

        BizException error = assertThrows(BizException.class, () -> service.acceptInvite("invite-1"));

        assertEquals("群已解散", error.getMessage());
        verify(followPort, never()).interrelated(any());
        verify(invites, never()).update(any(), any());
        verify(members, never()).insert(any(ConversationMember.class));
    }

    @Test
    void listInvitesCarriesTheStatusThrough() {
        UserContext.setUser(String.valueOf(INVITEE));
        ConversationInvite accepted = pendingInvite();
        accepted.setStatus(InviteStatusEnum.ACCEPTED);
        ConversationInvite rejected = pendingInvite();
        rejected.setStatus(InviteStatusEnum.REJECTED);
        when(invites.selectList(any())).thenReturn(new ArrayList<>(List.of(pendingInvite(), accepted, rejected)));
        when(conversations.selectList(any())).thenReturn(List.of(group(9L)));
        UserSummary inviter = new UserSummary();
        inviter.setId(INVITER);
        inviter.setNickname("阿三");
        when(userPort.listByIds(any())).thenReturn(List.of(inviter));

        List<ConversationInviteVO> vos = service.listInvites(null);

        assertEquals(3, vos.size());
        assertEquals(InviteStatusEnum.PENDING, vos.get(0).getStatus());
        assertEquals(InviteStatusEnum.ACCEPTED, vos.get(1).getStatus());
        assertEquals(InviteStatusEnum.REJECTED, vos.get(2).getStatus());
    }

    @Test
    void rejectOnlyChangesTheInvite() {
        UserContext.setUser(String.valueOf(INVITEE));
        when(invites.selectById("invite-1")).thenReturn(pendingInvite());
        when(invites.update(any(), any())).thenReturn(1);

        service.rejectInvite("invite-1");

        verify(invites).update(any(), any());
        verify(members, never()).insert(any(ConversationMember.class));
        verify(members, never()).update(any(), any());
    }

    private static Conversation group(long lastSeq) {
        Conversation conversation = new Conversation();
        conversation.setId("group");
        conversation.setType(ConversationTypeEnum.GROUP);
        conversation.setName("周末局");
        conversation.setLastSeq(lastSeq);
        return conversation;
    }

    private static ConversationInvite pendingInvite() {
        ConversationInvite invite = new ConversationInvite();
        invite.setId("invite-1");
        invite.setConversationId("group");
        invite.setInviterId(INVITER);
        invite.setInviteeId(INVITEE);
        invite.setStatus(InviteStatusEnum.PENDING);
        return invite;
    }
}

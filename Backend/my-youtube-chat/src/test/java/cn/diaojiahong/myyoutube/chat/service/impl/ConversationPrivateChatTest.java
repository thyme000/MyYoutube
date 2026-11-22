package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.domain.dto.CreateConversationDTO;
import cn.diaojiahong.myyoutube.chat.domain.dto.PrivateInboxHideState;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationPrivateChatTest {

    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final ConversationMemberMapper members = mock(ConversationMemberMapper.class);
    private final PushFanoutPublisher push = mock(PushFanoutPublisher.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final PrivateConversationService privateConversationService = new PrivateConversationService(
            conversations, members, mock(UserPort.class), mock(UserBlockPort.class));
    private final ConversationService service = new ConversationService(
            conversations, members, privateConversationService, mock(FollowPort.class), mock(UserPort.class),
            mock(S3StorageUtil.class), mock(RocketMQTemplate.class), push, transactions);

    @BeforeAll
    static void registerColumns() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Conversation.class);
        TableInfoHelper.initTableInfo(assistant, ConversationMember.class);
    }

    @BeforeEach
    void signIn() {
        UserContext.setUser("11");
    }

    @AfterEach
    void signOut() {
        UserContext.removeUser();
    }

    @Test
    void deletingAPrivateChatHidesOnlyTheCallerAtTheSeenTail() {
        when(conversations.selectById("chat")).thenReturn(single());
        when(members.selectActiveUserIds("chat")).thenReturn(List.of(11L, 22L));
        when(members.findInboxHideState("chat", 11L)).thenReturn(hideState("mine", "msg-1", 4L, true));
        when(members.hideInbox(eq("mine"), eq("msg-1"), eq(4L), any())).thenReturn(1);

        assertEquals("删除成功", service.deleteConversation("chat"));

        verify(members).hideInbox(eq("mine"), eq("msg-1"), eq(4L), any());
        verify(members, never()).update(any(), any());
        verify(members, never()).selectById(any());
    }

    @Test
    void hidingAgainSucceedsWhenTheInboxIsAlreadyHidden() {
        when(conversations.selectById("chat")).thenReturn(single());
        when(members.selectActiveUserIds("chat")).thenReturn(List.of(11L, 22L));
        when(members.findInboxHideState("chat", 11L)).thenReturn(hideState("mine", "msg-1", 4L, false));

        assertEquals("删除成功", service.deleteConversation("chat"));

        verify(members, never()).hideInbox(any(), any(), anyLong(), any());
    }

    @Test
    void hidingSucceedsWhenTheRowIsHiddenBeforeTheUpdateLands() {
        when(conversations.selectById("chat")).thenReturn(single());
        when(members.selectActiveUserIds("chat")).thenReturn(List.of(11L, 22L));
        when(members.findInboxHideState("chat", 11L)).thenReturn(hideState("mine", "msg-1", 4L, true));
        when(members.hideInbox(eq("mine"), eq("msg-1"), eq(4L), any())).thenReturn(0);
        ConversationMember current = member("mine", false, "msg-1");
        when(members.selectById("mine")).thenReturn(current);

        assertEquals("删除成功", service.deleteConversation("chat"));

        verify(members, times(1)).hideInbox(eq("mine"), eq("msg-1"), eq(4L), any());
    }

    @Test
    void hidingFailsWhenTheTailChangesAfterTheRead() {
        when(conversations.selectById("chat")).thenReturn(single());
        when(members.selectActiveUserIds("chat")).thenReturn(List.of(11L, 22L));
        when(members.findInboxHideState("chat", 11L)).thenReturn(hideState("mine", "msg-1", 4L, true));
        when(members.hideInbox(eq("mine"), eq("msg-1"), eq(4L), any())).thenReturn(0);
        when(members.selectById("mine")).thenReturn(member("mine", true, "msg-2"));

        BizException error = assertThrows(BizException.class, () -> service.deleteConversation("chat"));

        assertEquals(ErrorCode.STATE_CONFLICT, error.getErrorCode());
        assertEquals("会话有新消息，未能隐藏", error.getMessage());
        verify(members, times(1)).hideInbox(eq("mine"), eq("msg-1"), eq(4L), any());
    }

    @Test
    void hidingRejectsWhenTheMemberIsRemovedBeforeTheUpdateLands() {
        when(conversations.selectById("chat")).thenReturn(single());
        when(members.selectActiveUserIds("chat")).thenReturn(List.of(11L, 22L));
        when(members.findInboxHideState("chat", 11L)).thenReturn(hideState("mine", "msg-1", 4L, true));
        when(members.hideInbox(eq("mine"), eq("msg-1"), eq(4L), any())).thenReturn(0);
        ConversationMember removed = member("mine", true, "msg-1");
        removed.setDeleted(true);
        when(members.selectById("mine")).thenReturn(removed);

        BizException error = assertThrows(BizException.class, () -> service.deleteConversation("chat"));

        assertEquals(ErrorCode.FORBIDDEN, error.getErrorCode());
        assertEquals("不是会话成员", error.getMessage());
        verify(members, times(1)).hideInbox(eq("mine"), eq("msg-1"), eq(4L), any());
    }

    @Test
    void hidingAMissingOrDeletedMemberKeepsTheExistingPermissionError() {
        when(members.findInboxHideState("chat", 11L)).thenReturn(null);

        BizException missing = assertThrows(BizException.class,
                () -> privateConversationService.hidePrivateInbox("chat", 11L));

        assertEquals(ErrorCode.FORBIDDEN, missing.getErrorCode());
        assertEquals("不是会话成员", missing.getMessage());

        PrivateInboxHideState deleted = hideState("mine", "msg-1", 4L, true);
        deleted.setDeleted(true);
        when(members.findInboxHideState("chat", 11L)).thenReturn(deleted);
        BizException gone = assertThrows(BizException.class,
                () -> privateConversationService.hidePrivateInbox("chat", 11L));
        assertEquals(ErrorCode.FORBIDDEN, gone.getErrorCode());
        assertEquals("不是会话成员", gone.getMessage());
        verify(members, never()).hideInbox(any(), any(), anyLong(), any());
    }

    @Test
    void creatingAPrivateChatIsRejected() {
        BizException error = assertThrows(BizException.class, () -> service.createConversation(privateRequest()));

        assertEquals("该接口仅用于创建群聊", error.getMessage());
        verify(conversations, never()).insert(any(Conversation.class));
        verify(members, never()).insert(any(ConversationMember.class));
    }

    @Test
    void showingAHiddenSessionDoesNotInsertAnyRows() {
        ConversationMember mine = new ConversationMember();
        mine.setId("mine");
        mine.setDeleted(true);
        when(members.selectOne(any())).thenReturn(mine);

        privateConversationService.setPrivateInboxVisible("chat", 11L, true);

        verify(conversations, never()).insert(any(Conversation.class));
        verify(members).update(isNull(), any());
        verify(members, never()).insert(any(ConversationMember.class));
    }

    @Test
    void banningSomeoneOutsideTheGroupDoesNotPush() {
        Conversation group = group();
        when(conversations.selectById("group")).thenReturn(group);
        when(members.selectActiveUserIds("group")).thenReturn(List.of(11L, 22L));

        BizException error = assertThrows(BizException.class,
                () -> service.banConversationMember(99L, "group", true));

        assertEquals("用户不在群中", error.getMessage());
        verify(members, never()).update(any(), any());
        verify(push, never()).pushWs(any(), any());
    }

    @Test
    void theOwnerCannotBeBanned() {
        when(conversations.selectById("group")).thenReturn(group());
        when(members.selectActiveUserIds("group")).thenReturn(List.of(11L, 22L));

        BizException error = assertThrows(BizException.class,
                () -> service.banConversationMember(11L, "group", true));

        assertEquals("不能禁言群主", error.getMessage());
        verify(push, never()).pushWs(any(), any());
    }

    private static CreateConversationDTO privateRequest() {
        CreateConversationDTO dto = new CreateConversationDTO();
        dto.setType(ConversationTypeEnum.SINGLE);
        dto.setMemberIds(List.of(22L));
        return dto;
    }

    private static PrivateInboxHideState hideState(String memberId, String lastMessageId, long lastSeq, boolean visible) {
        PrivateInboxHideState state = new PrivateInboxHideState();
        state.setMemberId(memberId);
        state.setLastMessageId(lastMessageId);
        state.setLastSeq(lastSeq);
        state.setInboxVisible(visible);
        state.setDeleted(false);
        return state;
    }

    private static ConversationMember member(String id, boolean visible, String lastMessageId) {
        ConversationMember member = new ConversationMember();
        member.setId(id);
        member.setDeleted(false);
        member.setInboxVisible(visible);
        member.setLastMessageId(lastMessageId);
        return member;
    }

    private static Conversation single() {
        Conversation conversation = new Conversation();
        conversation.setId("chat");
        conversation.setType(ConversationTypeEnum.SINGLE);
        return conversation;
    }

    private static Conversation group() {
        Conversation conversation = new Conversation();
        conversation.setId("group");
        conversation.setType(ConversationTypeEnum.GROUP);
        conversation.setCreatedBy(11L);
        return conversation;
    }
}

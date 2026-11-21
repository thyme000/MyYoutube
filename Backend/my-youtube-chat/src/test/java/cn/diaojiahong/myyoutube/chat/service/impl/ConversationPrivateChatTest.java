package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.domain.dto.CreateConversationDTO;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void deletingAPrivateChatOnlyMarksTheCaller() {
        when(conversations.selectById("chat")).thenReturn(single());
        when(members.selectActiveUserIds("chat")).thenReturn(List.of(11L, 22L));

        service.deleteConversation("chat");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ConversationMember>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(members).update(isNull(), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSegment().contains("user_id"));
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

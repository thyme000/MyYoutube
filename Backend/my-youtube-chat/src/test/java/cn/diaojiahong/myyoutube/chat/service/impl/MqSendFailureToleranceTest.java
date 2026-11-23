package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageWithdrawDTO;
import cn.diaojiahong.myyoutube.chat.domain.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.MessageStatusEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.domain.po.Message;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.mapper.MessageMapper;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.mq.message.GroupDeletedMessage;
import cn.diaojiahong.myyoutube.mq.message.ReadMessageMessage;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.content.api.ContentInternalPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 撤回/解散/已读三条路径的 MQ 推送都是尽力而为:
 * 库写成功后推送失败只记 warn, 接口照常返回; 库写失败仍按原逻辑报错。
 */
class MqSendFailureToleranceTest {

    private static final String CONVERSATION_ID = "conversation";
    private static final String MESSAGE_ID = "message";
    private static final Long OPERATOR_ID = 7L;

    final MessageMapper messages = mock(MessageMapper.class);
    final ConversationMapper conversations = mock(ConversationMapper.class);
    final ConversationMemberMapper members = mock(ConversationMemberMapper.class);
    final ConversationService chatConversations = mock(ConversationService.class);
    final RocketMQTemplate mq = mock(RocketMQTemplate.class);
    final TransactionTemplate tx = txRunningCallback();

    @BeforeAll
    static void registerColumns() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Message.class);
        TableInfoHelper.initTableInfo(assistant, Conversation.class);
        TableInfoHelper.initTableInfo(assistant, ConversationMember.class);
    }

    @BeforeEach
    void signIn() {
        UserContext.setUser(String.valueOf(OPERATOR_ID));
    }

    @AfterEach
    void signOut() {
        UserContext.removeUser();
    }

    @Test
    void withdrawReturnsAckWhenMqSendFails() {
        when(messages.selectById(MESSAGE_ID)).thenReturn(sentMessage());
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(privateConversation()));
        when(members.selectOne(any())).thenReturn(activeMember());
        when(messages.update(isNull(), any())).thenReturn(1);
        when(mq.syncSend(anyString(), any(Object.class))).thenThrow(new IllegalStateException("MQ unavailable"));

        ChatMessageWithdrawDTO dto = new ChatMessageWithdrawDTO();
        dto.setMessageId(MESSAGE_ID);
        ChatMessageAckDTO ack = messageService().withdraw(dto);

        assertEquals(MESSAGE_ID, ack.getMessageId());
        assertEquals(MessageStatusEnum.WITHDRAWN, ack.getStatus());
        verify(messages).update(isNull(), any());
        verify(members).update(isNull(), any());
        verify(mq, times(1)).syncSend(eq(ChatMessageMessage.TOPIC), any(ChatMessageMessage.class));
    }

    @Test
    void withdrawStillFailsWhenDbWriteFails() {
        when(messages.selectById(MESSAGE_ID)).thenReturn(sentMessage());
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(privateConversation()));
        when(members.selectOne(any())).thenReturn(activeMember());
        when(messages.update(isNull(), any())).thenThrow(new DataIntegrityViolationException("db down"));

        ChatMessageWithdrawDTO dto = new ChatMessageWithdrawDTO();
        dto.setMessageId(MESSAGE_ID);
        assertThrows(DataIntegrityViolationException.class, () -> messageService().withdraw(dto));

        verify(mq, never()).syncSend(anyString(), any(Object.class));
    }

    @Test
    void dissolveReturnsSuccessWhenMqSendFails() {
        Conversation group = group();
        when(conversations.selectById(CONVERSATION_ID)).thenReturn(group);
        when(members.selectActiveUserIds(CONVERSATION_ID)).thenReturn(List.of(OPERATOR_ID, 22L));
        when(conversations.lockById(CONVERSATION_ID)).thenReturn(group);
        when(conversations.update(isNull(), any())).thenReturn(1);
        when(members.update(isNull(), any())).thenReturn(1);
        when(mq.syncSend(anyString(), any(Object.class))).thenThrow(new IllegalStateException("MQ unavailable"));

        String result = conversationService().deleteConversation(CONVERSATION_ID);

        assertEquals("删除成功", result);
        verify(conversations).update(isNull(), any());
        verify(members).update(isNull(), any());
        verify(mq, times(1)).syncSend(eq(GroupDeletedMessage.TOPIC), any(GroupDeletedMessage.class));
    }

    @Test
    void dissolveStillFailsWhenDbWriteFails() {
        Conversation group = group();
        when(conversations.selectById(CONVERSATION_ID)).thenReturn(group);
        when(members.selectActiveUserIds(CONVERSATION_ID)).thenReturn(List.of(OPERATOR_ID, 22L));
        when(conversations.lockById(CONVERSATION_ID)).thenReturn(group);
        when(conversations.update(isNull(), any())).thenReturn(1);
        when(members.update(isNull(), any())).thenThrow(new DataIntegrityViolationException("db down"));

        assertThrows(DataIntegrityViolationException.class,
                () -> conversationService().deleteConversation(CONVERSATION_ID));

        verify(mq, never()).syncSend(anyString(), any(Object.class));
    }

    @Test
    void readReturnsQuietlyWhenMqSendFails() {
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(privateConversation()));
        when(members.markRead(eq(CONVERSATION_ID), eq(OPERATOR_ID), eq(6L), eq(10L), any())).thenReturn(1);
        ConversationMember member = new ConversationMember();
        member.setLastReadSeq(6L);
        when(members.selectOne(any())).thenReturn(member);
        when(mq.syncSend(anyString(), any(Object.class))).thenThrow(new IllegalStateException("MQ unavailable"));

        assertDoesNotThrow(() -> messageService().read(CONVERSATION_ID, 6L));

        verify(members).markRead(eq(CONVERSATION_ID), eq(OPERATOR_ID), eq(6L), eq(10L), any());
        verify(mq, times(1)).syncSend(eq(ReadMessageMessage.TOPIC), any(ReadMessageMessage.class));
    }

    @Test
    void readDoesNotNotifyWhenWatermarkDoesNotAdvance() {
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(privateConversation()));
        when(members.selectOne(any())).thenReturn(activeMember());
        when(members.markRead(eq(CONVERSATION_ID), eq(OPERATOR_ID), eq(6L), eq(10L), any())).thenReturn(0);

        messageService().read(CONVERSATION_ID, 6L);

        verify(mq, never()).syncSend(anyString(), any(Object.class));
    }

    @Test
    void readStillFailsWhenDbWriteFails() {
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(privateConversation()));
        when(members.selectOne(any())).thenReturn(activeMember());
        when(members.markRead(eq(CONVERSATION_ID), eq(OPERATOR_ID), eq(6L), eq(10L), any()))
                .thenThrow(new DataIntegrityViolationException("db down"));

        assertThrows(DataIntegrityViolationException.class, () -> messageService().read(CONVERSATION_ID, 6L));

        verify(mq, never()).syncSend(anyString(), any(Object.class));
    }

    @Test
    void readRejectsWhenCallerIsNotAnActiveMember() {
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(privateConversation()));
        when(members.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> messageService().read(CONVERSATION_ID, 6L));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals("不是会话成员", ex.getMessage());
        verify(members, never()).markRead(any(), any(), anyLong(), anyLong(), any());
    }

    private ChatMessageService messageService() {
        return new ChatMessageService(messages, conversations, members, chatConversations,
                new PrivateConversationService(conversations, members, mock(UserPort.class), mock(UserBlockPort.class)),
                mq, mock(UserPort.class), mock(ContentInternalPort.class),
                mock(ChatContentTypeStrategyRegistry.class), mock(SyncChainStepRegistry.class),
                tx, mock(UserBlockPort.class), mock(FollowPort.class));
    }

    private ConversationService conversationService() {
        return new ConversationService(conversations, members,
                new PrivateConversationService(conversations, members, mock(UserPort.class), mock(UserBlockPort.class)),
                mock(FollowPort.class), mock(UserPort.class),
                mock(S3StorageUtil.class), mq, mock(PushFanoutPublisher.class), tx);
    }

    private static TransactionTemplate txRunningCallback() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any()))
                .thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        return tx;
    }

    private static Message sentMessage() {
        Message message = new Message();
        message.setId(MESSAGE_ID);
        message.setConversationId(CONVERSATION_ID);
        message.setSenderId(OPERATOR_ID);
        message.setContentType(ChatContentTypeEnum.TEXT);
        message.setSeq(5L);
        message.setStatus(MessageStatusEnum.SENT);
        message.setRecalled(false);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private static ConversationMember activeMember() {
        ConversationMember member = new ConversationMember();
        member.setUserId(OPERATOR_ID);
        member.setDeleted(false);
        return member;
    }

    private static Conversation privateConversation() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setType(ConversationTypeEnum.SINGLE);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setLastSeq(10L);
        conversation.setUserA(OPERATOR_ID);
        conversation.setUserB(8L);
        return conversation;
    }

    private static Conversation group() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setType(ConversationTypeEnum.GROUP);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setCreatedBy(OPERATOR_ID);
        return conversation;
    }
}

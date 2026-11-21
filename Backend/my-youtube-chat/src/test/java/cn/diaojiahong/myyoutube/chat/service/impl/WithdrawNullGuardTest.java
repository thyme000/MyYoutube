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
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 撤回对缺失数据的两处兜底: 没有发送时间无法判定时限按冲突拒绝; 条件更新未命中靠回查裁决。
 */
class WithdrawNullGuardTest {

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
    void withdrawWithoutCreatedAtRejectsWithStateConflict() {
        Message message = sentMessage();
        message.setCreatedAt(null);
        when(messages.selectById(MESSAGE_ID)).thenReturn(message);
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(privateConversation()));
        when(members.selectActiveUserIds(CONVERSATION_ID)).thenReturn(List.of(OPERATOR_ID, 8L));

        BizException ex = assertThrows(BizException.class, () -> messageService().withdraw(dto()));

        assertEquals(ErrorCode.STATE_CONFLICT, ex.getErrorCode());
        assertEquals("消息缺少发送时间，无法撤回", ex.getMessage());
        verify(messages, never()).update(isNull(), any());
    }

    @Test
    void withdrawByGroupOwnerSkipsTimeLimitWithoutCreatedAt() {
        Message message = sentMessage();
        message.setCreatedAt(null);
        when(messages.selectById(MESSAGE_ID)).thenReturn(message);
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(group()));
        when(members.selectActiveUserIds(CONVERSATION_ID)).thenReturn(List.of(OPERATOR_ID, 8L));
        when(messages.update(isNull(), any())).thenReturn(1);
        when(members.markTailRecalled(eq(CONVERSATION_ID), eq(MESSAGE_ID), any())).thenReturn(1);

        ChatMessageAckDTO ack = messageService().withdraw(dto());

        assertEquals(MessageStatusEnum.WITHDRAWN, ack.getStatus());
        verify(messages).update(isNull(), any());
        verify(members).markTailRecalled(eq(CONVERSATION_ID), eq(MESSAGE_ID), any());
    }

    @Test
    void withdrawFallsBackToCurrentRowWhenUpdateMisses() {
        when(messages.selectById(MESSAGE_ID)).thenReturn(sentMessage(), withdrawnMessage());
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(privateConversation()));
        when(members.selectActiveUserIds(CONVERSATION_ID)).thenReturn(List.of(OPERATOR_ID, 8L));
        when(messages.update(isNull(), any())).thenReturn(0);

        ChatMessageAckDTO ack = messageService().withdraw(dto());

        assertEquals(MESSAGE_ID, ack.getMessageId());
        assertEquals(MessageStatusEnum.WITHDRAWN, ack.getStatus());
    }

    @Test
    void withdrawRejectsWithNotFoundWhenRowVanishes() {
        when(messages.selectById(MESSAGE_ID)).thenReturn(sentMessage(), (Message) null);
        when(chatConversations.getById(CONVERSATION_ID)).thenReturn(Optional.of(privateConversation()));
        when(members.selectActiveUserIds(CONVERSATION_ID)).thenReturn(List.of(OPERATOR_ID, 8L));
        when(messages.update(isNull(), any())).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> messageService().withdraw(dto()));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals("消息不存在", ex.getMessage());
    }

    private ChatMessageService messageService() {
        return new ChatMessageService(messages, conversations, members, chatConversations,
                mock(PrivateConversationService.class), mq,
                mock(UserPort.class), mock(ContentInternalPort.class),
                mock(ChatContentTypeStrategyRegistry.class), mock(SyncChainStepRegistry.class),
                tx, mock(UserBlockPort.class), mock(FollowPort.class));
    }

    private static TransactionTemplate txRunningCallback() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any()))
                .thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        return tx;
    }

    private static ChatMessageWithdrawDTO dto() {
        ChatMessageWithdrawDTO dto = new ChatMessageWithdrawDTO();
        dto.setMessageId(MESSAGE_ID);
        return dto;
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

    private static Message withdrawnMessage() {
        Message message = sentMessage();
        message.setRecalled(true);
        message.setRecalledAt(LocalDateTime.now());
        message.setStatus(MessageStatusEnum.WITHDRAWN);
        return message;
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

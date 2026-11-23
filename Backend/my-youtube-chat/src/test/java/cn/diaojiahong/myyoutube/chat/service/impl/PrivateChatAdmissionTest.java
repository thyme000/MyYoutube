package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.chat.chain.message.IdempotentStep;
import cn.diaojiahong.myyoutube.chat.chain.message.MessageSendContext;
import cn.diaojiahong.myyoutube.chat.chain.message.MessageSendStep;
import cn.diaojiahong.myyoutube.chat.chain.message.TypeValidateStep;
import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.ChatContent;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.Message;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.mapper.MessageMapper;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.chat.service.strategy.TextChatContentStrategy;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.content.api.ContentInternalPort;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateChatAdmissionTest {

    private Conversation conversation;
    private ConversationMapper conversations;
    private MessageMapper messages;
    private ChatMessageService service;
    private boolean mutual;

    @BeforeEach
    void setup() {
        UserContext.setUser("7");
        mutual = false;
        conversation = new Conversation();
        conversation.setId("conversation");
        conversation.setType(ConversationTypeEnum.SINGLE);
        conversation.setStatus(ConversationStatus.WAITING_REPLY);
        conversation.setCreatedBy(7L);
        messages = mock(MessageMapper.class);
        conversations = mock(ConversationMapper.class);
        when(conversations.advanceOnMessage(any(), any())).thenReturn(1);
        when(conversations.selectLastSeq(any())).thenReturn(1L);
        when(conversations.lockById("conversation")).thenAnswer(call -> lockedConversation(7L));
        var registry = new ChatContentTypeStrategyRegistry(List.of(new TextChatContentStrategy()));
        var prepare = new MessageSendStep(20) {
            @Override
            public void apply(MessageSendContext ctx) {
                ctx.setConversation(conversation);
                ctx.setInterrelated(mutual);
            }
        };
        var guards = new SyncChainStepRegistry(List.of(
                new TypeValidateStep(registry), prepare, new IdempotentStep(messages)));
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call ->
                call.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0).doInTransaction(null));
        // 准入语义(WAITING_FOR_REPLY/激活)由真实的私聊服务承载, 共享同一个 conversations 桩
        var privateConversation = new PrivateConversationService(conversations,
                mock(ConversationMemberMapper.class), mock(UserPort.class), mock(UserBlockPort.class));
        service = new ChatMessageService(messages, conversations, mock(ConversationMemberMapper.class),
                mock(ConversationService.class), privateConversation, mock(RocketMQTemplate.class),
                mock(UserPort.class), mock(ContentInternalPort.class), registry, guards, tx,
                mock(UserBlockPort.class), mock(FollowPort.class));
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
    }

    @Test
    void secondGreetingIsRejectedBeforeAnotherSequenceIsAllocated() {
        BizException rejected = assertThrows(BizException.class, () -> service.send(text("client-2")));

        assertEquals(ErrorCode.STATE_CONFLICT, rejected.getErrorCode());
        assertEquals(Conversation.WAITING_FOR_REPLY, rejected.getMessage());
        verify(conversations, never()).advanceOnMessage(any(), any());
        verify(messages, never()).insert(any(Message.class));
        verify(conversations, never()).activatePrivateChat(any());
    }

    @Test
    void secondMessageIsAllowedOnceTheyFollowEachOther() {
        mutual = true;

        service.send(text("client-2"));

        verify(conversations).advanceOnMessage(any(), any());
        verify(conversations).activatePrivateChat("conversation");
        verify(messages).insert(any(Message.class));
    }

    @Test
    void replyFromTheOtherPersonActivatesTheConversation() {
        when(conversations.lockById("conversation")).thenReturn(lockedConversation(8L));

        service.send(text("client-reply"));

        verify(conversations).activatePrivateChat("conversation");
        verify(conversations).advanceOnMessage(any(), any());
    }

    @Test
    void activeConversationSkipsTheAdmissionLock() {
        conversation.setStatus(ConversationStatus.ACTIVE);

        service.send(text("client-1"));

        verify(conversations, never()).lockById(any());
    }

    private Conversation lockedConversation(Long createdBy) {
        Conversation locked = new Conversation();
        locked.setId("conversation");
        locked.setType(ConversationTypeEnum.SINGLE);
        locked.setStatus(ConversationStatus.WAITING_REPLY);
        locked.setCreatedBy(createdBy);
        return locked;
    }

    private static ChatMessageDTO text(String clientMsgId) {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setConversationId("conversation");
        dto.setClientMsgId(clientMsgId);
        dto.setContentType(ChatContentTypeEnum.TEXT);
        ChatContent content = new ChatContent();
        content.setText("你好");
        dto.setContent(content);
        return dto;
    }
}

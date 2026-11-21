package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.MessageMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.domain.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.chain.message.*;
import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.domain.enums.*;
import cn.diaojiahong.myyoutube.chat.domain.po.*;
import cn.diaojiahong.myyoutube.chat.service.strategy.*;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardContentTest {
    final ContentInternalPort contents = mock(ContentInternalPort.class);

    final S3StorageUtil storage = mock(S3StorageUtil.class);

    final ForwardContentChatContentStrategy strategy = new ForwardContentChatContentStrategy(contents, storage);

    final MessageMapper messages = mock(MessageMapper.class);

    final RocketMQTemplate mq = mock(RocketMQTemplate.class);

    final AtomicReference<Message> saved = new AtomicReference<>();
    ChatMessageService service;

    @BeforeEach
    void setup() {
        UserContext.setUser("7");
        var registry = new ChatContentTypeStrategyRegistry(List.of(strategy));
        var conversation = new Conversation();
        conversation.setId("conversation");
        conversation.setType(ConversationTypeEnum.SINGLE);
        conversation.setCreatedBy(7L);
        conversation.setStatus(ConversationStatus.ACTIVE);
        var setupConversation = new MessageSendStep(20) {
            @Override
            public void apply(MessageSendContext ctx) {
                ctx.setConversation(conversation);
                ctx.setInterrelated(true);
            }
        };
        var guards = new SyncChainStepRegistry(List.of(new TypeValidateStep(registry), setupConversation,
                new IdempotentStep(messages)));
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any()))
                .thenAnswer(call -> call.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(messages.selectById(anyString())).thenAnswer(call -> saved.get());
        when(messages.insert(any(Message.class))).thenAnswer(call -> {
            Message doc = call.<Message>getArgument(0);
            saved.set(doc);
            return 1;
        });
        ConversationMapper conversations = mock(ConversationMapper.class);
        when(conversations.advanceOnMessage(any(), any())).thenReturn(1);
        when(conversations.selectLastSeq(any())).thenReturn(1L);
        service = spy(new ChatMessageService(messages, conversations,
                mock(ConversationMemberMapper.class),
                mock(ConversationService.class), mock(PrivateConversationService.class), mq,
                mock(UserPort.class), contents, registry, guards, tx,
                mock(UserBlockPort.class), mock(FollowPort.class)));
        when(contents.forwardInfo(99L, 7L)).thenReturn(new ContentInternalPort.ForwardInfo(
                99L,
                "dynamic",
                "真实标题",
                "chosen-cover"));
        when(contents.incrementShareCount(99L, 7L)).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
    }

    ChatMessageDTO request() {
        var dto = new ChatMessageDTO();
        dto.setConversationId("conversation");
        dto.setClientMsgId("client-message");
        dto.setContentType(ChatContentTypeEnum.FORWARD_CONTENT);
        var content = new ChatContent();
        content.setContentId(99L);
        content.setTitle("伪造标题");
        content.setMediaType("video");
        content.setCoverObjectKey("forged-cover");
        content.setText("forged-text");
        dto.setContent(content);
        return dto;
    }

    @Test
    void snapshotIgnoresClientMetadataAndUrlSigningPreservesStoredKey() throws Exception {
        ChatContent snapshot = strategy.snapshot(request().getContent());
        assertEquals(99L, snapshot.getContentId());
        assertEquals("dynamic", snapshot.getMediaType());
        assertEquals("真实标题", snapshot.getTitle());
        assertNull(snapshot.getText());
        when(storage.signGetUrl("chosen-cover", 30L)).thenReturn("signed-cover");
        strategy.signUrls(snapshot, 30L);
        strategy.signUrls(snapshot, 30L);
        assertEquals("chosen-cover", snapshot.getCoverObjectKey());
        assertEquals("signed-cover", snapshot.getCoverUrl());
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(snapshot));
        // content 整体经 Jackson 序列化落库, 封面 key 必须在 JSON 里, 否则读回来永远拼不出 coverUrl
        assertEquals("chosen-cover", json.get("coverObjectKey").asText());
        assertEquals("signed-cover", json.get("coverUrl").asText());
        assertEquals("[转发动态]", strategy.previewLabel(snapshot));
        when(contents.forwardInfo(99L, 7L)).thenReturn(new ContentInternalPort.ForwardInfo(
                99L,
                "video",
                "视频",
                "cover"));
        assertEquals("[转发视频]", strategy.previewLabel(strategy.snapshot(request().getContent())));
    }

    @Test
    void sendAndRetryUseOneSnapshotOneMessageAndOneShareIncrement() {
        ChatMessageAckDTO first = service.send(request());
        ChatMessageAckDTO second = service.send(request());
        assertEquals(first.getMessageId(), second.getMessageId());
        assertEquals(first.getSeq(), second.getSeq());
        assertEquals("真实标题", saved.get().getContent().getTitle());
        verify(messages).insert(any(Message.class));
        verify(contents).forwardInfo(99L, 7L);
        verify(contents).incrementShareCount(99L, 7L);
    }

    @Test
    void inaccessibleSourceStopsBeforeAnyMessageOrCounterWrite() {
        when(contents.forwardInfo(99L, 7L)).thenThrow(new BizException(ErrorCode.INVALID_ARGUMENT, "内容不可访问"));
        assertThrows(BizException.class, () -> service.send(request()));
        verify(messages, never()).insert(any(Message.class));
        verify(contents, never()).incrementShareCount(anyLong(), anyLong());
    }

    @Test
    void retryAfterPushFailureDoesNotRepeatTheShareIncrement() {
        when(mq.syncSend(anyString(), any(Object.class))).thenThrow(new IllegalStateException("MQ unavailable"));
        String messageId = MessageSendContext.messageId(7L, "client-message");
        assertEquals(messageId, service.send(request()).getMessageId());
        assertEquals(messageId, service.send(request()).getMessageId());
        verify(contents).incrementShareCount(99L, 7L);
        verify(messages).insert(any(Message.class));
        verify(mq, times(1)).syncSend(anyString(), any(Object.class));
    }

    @Test
    void invalidForwardIdsAreRejectedBeforeRemoteLookup() {
        var content = new ChatContent();
        assertThrows(BizException.class, () -> strategy.validate(content));
        content.setContentId(0L);
        assertThrows(BizException.class, () -> strategy.validate(content));
        verifyNoInteractions(contents);
    }
}

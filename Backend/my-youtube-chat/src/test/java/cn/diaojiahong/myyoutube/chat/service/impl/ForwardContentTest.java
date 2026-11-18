package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.mapper.ChatConversationMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ChatConversationSeqMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ChatMessageMapper;
import cn.diaojiahong.myyoutube.chat.mapper.ChatUserConversationMapper;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ack.ChatMessageAckDTO;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.chain.message.*;
import cn.diaojiahong.myyoutube.chat.mongo.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.mongo.enums.*;
import cn.diaojiahong.myyoutube.chat.mongo.po.*;
import cn.diaojiahong.myyoutube.chat.service.strategy.*;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardContentTest {
    final ContentInternalPort contents = mock(ContentInternalPort.class);

    final S3StorageUtil storage = mock(S3StorageUtil.class);

    final ForwardContentChatContentStrategy strategy = new ForwardContentChatContentStrategy(contents, storage);

    final ChatMessageMapper messages = mock(ChatMessageMapper.class);

    final RocketMQTemplate mq = mock(RocketMQTemplate.class);

    final AtomicReference<ChatMessageDoc> saved = new AtomicReference<>();
    ChatMessageService service;

    @BeforeEach
    void setup() {
        UserContext.setUser("7");
        var registry = new ChatContentTypeStrategyRegistry(List.of(strategy));
        var conversation = new ChatConversationDoc();
        conversation.setId("conversation");
        conversation.setType(ConversationTypeEnum.SINGLE);
        var setupConversation = new MessageSendStep(20) {
            @Override
            public void apply(MessageSendContext ctx) {
                ctx.setConversationDoc(conversation);
                ctx.setInterrelated(true);
            }
        };
        var guards = new SyncChainStepRegistry(List.of(new TypeValidateStep(registry), setupConversation,
                new SenderIdentityStep(), new IdempotentStep(messages)));
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any()))
                .thenAnswer(call -> call.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(messages.selectById(anyString())).thenAnswer(call -> saved.get());
        when(messages.insert(any(ChatMessageDoc.class))).thenAnswer(call -> {
            ChatMessageDoc doc = call.<ChatMessageDoc>getArgument(0);
            saved.set(doc);
            return 1;
        });
        service = spy(new ChatMessageService(messages, mock(ChatConversationMapper.class),
                mock(ChatConversationSeqMapper.class), mock(ChatUserConversationMapper.class),
                mock(ChatConversationService.class), mq,
                mock(UserPort.class), storage, mock(StringRedisTemplate.class), contents, registry, guards, tx));
        doReturn(1L).when(service).nextSeq("conversation");
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
        dto.setSenderId(7L);
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
        assertFalse(json.has("coverObjectKey"));
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
        verify(messages).insert(any(ChatMessageDoc.class));
        verify(contents).forwardInfo(99L, 7L);
        verify(contents).incrementShareCount(99L, 7L);
    }

    @Test
    void senderCannotUseAnIdempotentReceiptToBypassIdentityCheck() {
        service.send(request());
        ChatMessageDTO spoof = request();
        spoof.setSenderId(8L);
        assertThrows(BizException.class, () -> service.send(spoof));
        verify(contents).incrementShareCount(99L, 7L);
    }

    @Test
    void inaccessibleSourceStopsBeforeAnyMessageOrCounterWrite() {
        when(contents.forwardInfo(99L, 7L)).thenThrow(new BizException(ErrorCode.INVALID_ARGUMENT, "内容不可访问"));
        assertThrows(BizException.class, () -> service.send(request()));
        verify(messages, never()).insert(any(ChatMessageDoc.class));
        verify(contents, never()).incrementShareCount(anyLong(), anyLong());
    }

    @Test
    void retryAfterPushFailureDoesNotRepeatTheShareIncrement() {
        when(mq.syncSend(anyString(), any(Object.class))).thenThrow(new IllegalStateException("MQ unavailable"));
        assertThrows(IllegalStateException.class, () -> service.send(request()));
        assertEquals(MessageSendContext.messageId(7L,"client-message"), service.send(request()).getMessageId());
        verify(contents).incrementShareCount(99L, 7L);
        verify(messages).insert(any(ChatMessageDoc.class));
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

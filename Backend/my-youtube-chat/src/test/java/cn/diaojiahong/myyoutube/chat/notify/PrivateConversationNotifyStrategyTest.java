package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.ChatEventType;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.RecallTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.handler.ChatWebSocketHandler;
import cn.diaojiahong.myyoutube.handler.LocalPushDispatcher;
import cn.diaojiahong.myyoutube.handler.SseEmitterRegistry;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.google.gson.JsonObject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PrivateConversationNotifyStrategyTest {

    @BeforeAll
    static void initTable() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConversationMember.class);
    }

    @Test
    void mutedPrivateMessageDoesNotPush() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        PushFanoutPublisher push = mock(PushFanoutPublisher.class);
        PrivateConversationNotifyStrategy strategy = new PrivateConversationNotifyStrategy(
                push, mock(S3StorageUtil.class), memberships);
        ConversationMember membership = new ConversationMember();
        membership.setMute(true);
        when(memberships.selectActiveUserIds("conversation")).thenReturn(List.of(7L, 8L));
        when(memberships.selectOne(any())).thenReturn(membership);

        Conversation conversation = new Conversation();
        conversation.setId("conversation");
        conversation.setType(ConversationTypeEnum.SINGLE);
        ChatMessageMessage message = ChatMessageMessage.builder()
                .conversationId("conversation")
                .senderId(7L)
                .contentType(ChatContentTypeEnum.TEXT)
                .content("晚上看")
                .event("send")
                .build();
        UserSummary sender = new UserSummary();
        sender.setNickname("thyme");

        strategy.notify(conversation, message, sender);

        verify(push, never()).pushWs(anyLong(), any());
        verify(push, never()).pushSseSignal(anyLong(), any());
        verify(push, never()).pushSseSignal(anyLong(), any(), any());
        verify(memberships).selectActiveUserIds("conversation");
    }

    @Test
    void firstMessageDeliversTheSamePayloadOnBothChannels() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        when(memberships.selectActiveUserIds("conversation")).thenReturn(List.of(7L, 8L));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        S3StorageUtil storage = mock(S3StorageUtil.class);
        String avatarKey = "avatars/sender.png";
        String signedAvatar = "https://storage.example.test/signed-avatar.png";
        when(storage.signGetUrl(eq(avatarKey), anyLong())).thenReturn(signedAvatar);
        PrivateConversationNotifyStrategy strategy = new PrivateConversationNotifyStrategy(
                new PushFanoutPublisher(redis), storage, memberships);
        Conversation conversation = new Conversation();
        conversation.setId("conversation");
        ChatMessageMessage message = ChatMessageMessage.builder()
                .conversationId("conversation")
                .messageId("9007199254740993")
                .seq(1L)
                .senderId(7L)
                .contentType(ChatContentTypeEnum.TEXT)
                .content("晚上看")
                .event(ChatEventType.SEND.getCode())
                .build();
        UserSummary sender = new UserSummary();
        sender.setNickname("thyme");
        sender.setAvatarUrl(avatarKey);

        strategy.notify(conversation, message, sender);

        ArgumentCaptor<String> wsEnvelope = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sseEnvelope = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(PushFanoutPublisher.CHANNEL_WS), wsEnvelope.capture());
        verify(redis).convertAndSend(eq(PushFanoutPublisher.CHANNEL_SSE), sseEnvelope.capture());
        verifyNoMoreInteractions(redis);
        ChatWebSocketHandler ws = mock(ChatWebSocketHandler.class);
        SseEmitterRegistry sse = mock(SseEmitterRegistry.class);
        LocalPushDispatcher dispatcher = new LocalPushDispatcher(ws, sse, new SimpleMeterRegistry());
        dispatch(dispatcher, PushFanoutPublisher.CHANNEL_WS, wsEnvelope.getValue());
        dispatch(dispatcher, PushFanoutPublisher.CHANNEL_SSE, sseEnvelope.getValue());

        ArgumentCaptor<String> wsFrame = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sseFrame = ArgumentCaptor.forClass(String.class);
        verify(ws).sendToUser(eq(8L), wsFrame.capture());
        verify(sse).sendToUser(eq(8L), sseFrame.capture());
        verifyNoMoreInteractions(ws, sse);

        JsonObject wsPayload = JsonUtil.fromJson(wsFrame.getValue(), JsonObject.class);
        JsonObject sseEvent = JsonUtil.fromJson(sseFrame.getValue(), JsonObject.class);
        assertEquals("CHAT_MESSAGE", sseEvent.get("type").getAsString());
        assertEquals(wsPayload, sseEvent.getAsJsonObject("payload"));
        assertEquals("9007199254740993", wsPayload.get("messageId").getAsString());
        assertEquals("conversation", wsPayload.get("conversationId").getAsString());
        assertEquals(1L, wsPayload.get("seq").getAsLong());
        assertEquals("晚上看", wsPayload.get("content").getAsString());
        assertEquals(signedAvatar, wsPayload.get("avatarUrl").getAsString());
    }

    @Test
    void withdrawalDoesNotSignOrSendAnAvatar() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        when(memberships.selectActiveUserIds("conversation")).thenReturn(List.of(7L, 8L));
        PushFanoutPublisher push = mock(PushFanoutPublisher.class);
        S3StorageUtil storage = mock(S3StorageUtil.class);
        PrivateConversationNotifyStrategy strategy = new PrivateConversationNotifyStrategy(push, storage, memberships);
        Conversation conversation = new Conversation();
        conversation.setId("conversation");
        UserSummary sender = new UserSummary();
        sender.setAvatarUrl("avatars/sender.png");
        ChatMessageMessage message = ChatMessageMessage.builder()
                .conversationId("conversation")
                .messageId("withdrawn-message")
                .senderId(7L)
                .recallType(RecallTypeEnum.SENDER)
                .event(ChatEventType.WITHDRAW.getCode())
                .build();

        strategy.notify(conversation, message, sender);

        verifyNoInteractions(storage);
        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(push).pushWs(eq(8L), frame.capture());
        verify(push).pushSseSignal(8L, AbstractConversationNotifyStrategy.CHAT_DIRTY_SIGNAL);
        JsonObject payload = JsonUtil.fromJson(frame.getValue(), JsonObject.class);
        assertEquals("withdrawn-message", payload.get("messageId").getAsString());
        assertFalse(payload.has("avatarUrl"));
    }

    private static void dispatch(LocalPushDispatcher dispatcher, String channel, String envelope) {
        Message broadcast = mock(Message.class);
        when(broadcast.getBody()).thenReturn(envelope.getBytes(StandardCharsets.UTF_8));
        dispatcher.onMessage(broadcast, channel.getBytes(StandardCharsets.UTF_8));
    }
}

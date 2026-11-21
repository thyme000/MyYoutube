package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.chat.domain.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateConversationNotifyStrategyTest {

    @Test
    void mutedPrivateMessageDoesNotPush() {
        ConversationMemberMapper memberships = mock(ConversationMemberMapper.class);
        PushFanoutPublisher push = mock(PushFanoutPublisher.class);
        PrivateConversationNotifyStrategy strategy = new PrivateConversationNotifyStrategy(
                push, mock(S3StorageUtil.class), memberships);
        ConversationMember membership = new ConversationMember();
        membership.setMute(true);
        when(memberships.selectActiveUserIds("conversation")).thenReturn(new ArrayList<>(List.of(7L, 8L)));
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
    }
}

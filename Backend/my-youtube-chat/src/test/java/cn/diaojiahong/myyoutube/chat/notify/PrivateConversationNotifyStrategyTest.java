package cn.diaojiahong.myyoutube.chat.notify;

import cn.diaojiahong.myyoutube.chat.mapper.ChatUserConversationMapper;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ChatContentTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.mq.message.ChatMessageMessage;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateConversationNotifyStrategyTest {

    @Test
    void mutedPrivateMessageStillRecordsUnreadWithoutPushing() {
        ChatUserConversationMapper memberships = mock(ChatUserConversationMapper.class);
        PushFanoutPublisher push = mock(PushFanoutPublisher.class);
        PrivateConversationNotifyStrategy strategy = new PrivateConversationNotifyStrategy(
                push, mock(S3StorageUtil.class), memberships);
        ChatUserConversationDoc membership = new ChatUserConversationDoc();
        membership.setMute(true);
        when(memberships.selectOne(any())).thenReturn(membership);

        ChatConversationDoc conversation = new ChatConversationDoc();
        conversation.setId("conversation");
        conversation.setType(ConversationTypeEnum.SINGLE);
        conversation.setMemberIds(List.of(7L, 8L));
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
        verify(memberships).touchMembers(eq("conversation"), eq(List.of(8L)), any(), eq(true));
    }
}

package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.domain.dto.ChatMessageDTO;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterrelatedRateLimitStepTest {

    private final FollowPort followPort = mock(FollowPort.class);
    private final InterrelatedRateLimitStep step =
            new InterrelatedRateLimitStep(followPort, mock(UserBlockPort.class));

    @Test
    void activePrivateChatWithAnInitiatorDoesNotCheckFollowStatus() {
        step.apply(context(ConversationStatus.ACTIVE, 7L, 7L));

        verify(followPort, never()).interrelated(any());
    }

    /** 空私聊还没有首发人: 无论谁发首条, 互关决定首发后的会话状态。 */
    @Test
    void emptyConversationChecksFollowStatusWhoeverSends() {
        when(followPort.interrelated(any())).thenReturn(true);
        MessageSendContext ctx = context(ConversationStatus.ACTIVE, 8L, null);

        step.apply(ctx);

        assertTrue(ctx.isInterrelated());
    }

    @Test
    void waitingInitiatorChecksFollowStatus() {
        when(followPort.interrelated(any())).thenReturn(false);
        MessageSendContext ctx = context(ConversationStatus.WAITING_REPLY, 7L, 7L);

        step.apply(ctx);

        assertFalse(ctx.isInterrelated());
    }

    @Test
    void recipientCanReplyWithoutCheckingFollowStatus() {
        step.apply(context(ConversationStatus.WAITING_REPLY, 8L, 7L));

        verify(followPort, never()).interrelated(any());
    }

    private MessageSendContext context(ConversationStatus status, Long senderId, Long initiatorId) {
        ChatMessageDTO dto = new ChatMessageDTO();
        dto.setConversationId("conversation");
        MessageSendContext ctx = MessageSendContext.of(dto, String.valueOf(senderId), "conversation");
        Conversation conversation = new Conversation();
        conversation.setId("conversation");
        conversation.setType(ConversationTypeEnum.SINGLE);
        conversation.setStatus(status);
        conversation.setCreatedBy(initiatorId);
        conversation.setUserA(7L);
        conversation.setUserB(8L);
        ctx.setConversation(conversation);
        return ctx;
    }
}

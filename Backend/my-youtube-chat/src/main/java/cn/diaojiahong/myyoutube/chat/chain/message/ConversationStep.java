package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationStatus;
import cn.diaojiahong.myyoutube.chat.service.impl.ConversationService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class ConversationStep extends MessageSendStep {

    private final ConversationService chatConversationService;

    public ConversationStep(ConversationService chatConversationService) {
        super(70);
        this.chatConversationService = chatConversationService;
    }

    @Override
    public void apply(MessageSendContext ctx) {
        Conversation conversation =
                chatConversationService.getById(ctx.getConversationId()).orElse(null);
        if (conversation == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }

        if (conversation.getStatus() == ConversationStatus.CLOSED) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "会话已关闭");
        }

        ctx.setConversation(conversation);
    }
}

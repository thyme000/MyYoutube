package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.chat.service.impl.ChatConversationService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class ConversationStep extends MessageSendStep {

    private final ChatConversationService chatConversationService;

    public ConversationStep(ChatConversationService chatConversationService) {
        super(70);
        this.chatConversationService = chatConversationService;
    }

    @Override
    public void apply(MessageSendContext ctx) {
        ChatConversationDoc conversationDoc =
                chatConversationService.getById(ctx.getConversationId()).orElse(null);
        if (conversationDoc == null) {
            throw new BizException(BizException.CONVERSATION_ERROR, "会话不存在");
        }
        ctx.setConversationDoc(conversationDoc);
    }
}

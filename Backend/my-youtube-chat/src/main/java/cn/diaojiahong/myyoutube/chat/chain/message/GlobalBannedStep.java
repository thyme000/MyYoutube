package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class GlobalBannedStep extends MessageSendStep {

    public GlobalBannedStep() {
        super(90);
    }

    @Override
    public void apply(MessageSendContext ctx) {
        var conversation = ctx.getConversationDoc();
        // 全员禁言只拦普通成员, 创建人不受限
        if (Boolean.TRUE.equals(conversation.getBanned())
                && !conversation.getCreatedBy().equals(Long.valueOf(ctx.getUserId()))) {
            throw new BizException(BizException.CONVERSATION_ERROR, "全员禁言开启中");
        }
    }
}

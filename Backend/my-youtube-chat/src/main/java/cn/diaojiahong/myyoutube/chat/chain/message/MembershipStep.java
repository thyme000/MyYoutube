package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class MembershipStep extends MessageSendStep {

    public MembershipStep() {
        super(80);
    }

    @Override
    public void apply(MessageSendContext ctx) {
        if (!ctx.getConversationDoc().getMemberIds().contains(ctx.getDto().getSenderId())) {
            throw new BizException(BizException.CONVERSATION_ERROR, "不是该会话的成员");
        }
    }
}

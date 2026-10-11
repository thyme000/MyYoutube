package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class SenderIdentityStep extends MessageSendStep {

    public SenderIdentityStep() {
        super(45);
    }

    @Override
    public void apply(MessageSendContext ctx) {
        if (ctx.getDto().getSenderId() == null || !ctx.getDto().getSenderId().toString().equals(ctx.getUserId())) {
            throw new BizException(BizException.MESSAGE_ERROR, "不是发送人");
        }
    }
}

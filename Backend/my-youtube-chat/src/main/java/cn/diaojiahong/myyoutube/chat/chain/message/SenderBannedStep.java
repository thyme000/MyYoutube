package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class SenderBannedStep extends MessageSendStep {

    public SenderBannedStep() {
        super(30);
    }

    @Override
    public void apply(MessageSendContext ctx) {
        if (Boolean.TRUE.equals(ctx.getUserConversationDoc().getBanned())) {
            throw new BizException(ErrorCode.FORBIDDEN, "您已被禁言");
        }
    }
}

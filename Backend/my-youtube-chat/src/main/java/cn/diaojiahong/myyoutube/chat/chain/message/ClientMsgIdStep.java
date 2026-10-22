package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class ClientMsgIdStep extends MessageSendStep {

    public ClientMsgIdStep() {
        super(40);
    }

    @Override
    public void apply(MessageSendContext ctx) {
        if (ctx.getDto().getClientMsgId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "客户端幂等id不能为空");
        }
    }
}

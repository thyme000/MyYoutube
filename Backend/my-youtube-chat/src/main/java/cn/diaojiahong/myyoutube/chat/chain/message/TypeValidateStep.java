package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategy;
import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class TypeValidateStep extends MessageSendStep {

    private final ChatContentTypeStrategyRegistry contentTypeStrategies;

    public TypeValidateStep(ChatContentTypeStrategyRegistry contentTypeStrategies) {
        super(10);
        this.contentTypeStrategies = contentTypeStrategies;
    }

    @Override
    public void apply(MessageSendContext ctx) {
        ChatContentTypeStrategy strategy = contentTypeStrategies.get(ctx.getDto().getContentType());
        if (strategy == null) {
            throw new BizException(BizException.MESSAGE_ERROR, "消息类型错误");
        }
        strategy.validate(ctx.getDto().getContent());
    }
}

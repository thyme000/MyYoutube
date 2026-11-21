package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.service.strategy.ChatContentTypeStrategyRegistry;
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
        contentTypeStrategies.require(ctx.getDto().getContentType()).validate(ctx.getDto().getContent());
    }
}

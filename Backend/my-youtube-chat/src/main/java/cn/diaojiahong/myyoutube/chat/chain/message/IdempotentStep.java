package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatMessageDoc;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdempotentStep extends MessageSendStep {

    private final MongoTemplate mongoTemplate;

    public IdempotentStep(MongoTemplate mongoTemplate) {
        super(50);
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 同一 clientMsgId 已发送过则把旧 ack 放进上下文: 不是异常, 而是
     * stop 谓词命中的"成功短路", 调用方直接返回旧 ack 不再写入。
     */
    @Override
    public void apply(MessageSendContext ctx) {
        ChatMessageDoc old = mongoTemplate.findById(
                MessageSendContext.messageId(Long.valueOf(ctx.getUserId()), ctx.getDto().getClientMsgId()),
                ChatMessageDoc.class);
        if (old == null) {
            return;
        }

        if (!old.getConversationId().equals(ctx.getConversationId())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "会话id不一致");
        }

        ctx.setAck(MessageSendContext.ackOf(old, old.getStatus()));
    }
}

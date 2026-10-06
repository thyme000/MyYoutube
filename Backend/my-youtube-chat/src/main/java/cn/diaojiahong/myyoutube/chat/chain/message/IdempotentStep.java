package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatMessageDoc;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
        Query q = Query.query(Criteria.where("senderId").is(Long.valueOf(ctx.getUserId()))
                .and("clientMsgId").is(ctx.getDto().getClientMsgId()));
        ChatMessageDoc old = mongoTemplate.findOne(q, ChatMessageDoc.class);
        if (old == null) {
            return;
        }
        if (!old.getConversationId().equals(ctx.getConversationId())) {
            throw new BizException(BizException.MESSAGE_ERROR, "会话id不一致");
        }
        ctx.setAck(MessageSendContext.ackOf(old, old.getStatus()));
    }
}

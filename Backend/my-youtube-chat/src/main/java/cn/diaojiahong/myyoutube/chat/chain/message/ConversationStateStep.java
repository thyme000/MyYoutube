package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class ConversationStateStep extends MessageSendStep {

    private final MongoTemplate mongoTemplate;

    public ConversationStateStep(MongoTemplate mongoTemplate) {
        super(20);
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void apply(MessageSendContext ctx) {
        Query q = Query.query(Criteria.where("conversationId").is(ctx.getConversationId())
                .and("userId").is(Long.valueOf(ctx.getUserId())));
        ChatUserConversationDoc userConversationDoc =
                mongoTemplate.findOne(q, ChatUserConversationDoc.class);
        if (userConversationDoc == null || Boolean.TRUE.equals(userConversationDoc.getDeleted())) {
            throw new BizException(BizException.CONVERSATION_ERROR, "不合法的会话状态");
        }
        ctx.setUserConversationDoc(userConversationDoc);
    }
}

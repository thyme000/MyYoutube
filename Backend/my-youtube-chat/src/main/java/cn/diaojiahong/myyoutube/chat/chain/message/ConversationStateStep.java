package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.mapper.ChatUserConversationMapper;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatUserConversationDoc;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

@Component
public class ConversationStateStep extends MessageSendStep {

    private final ChatUserConversationMapper userConversationMapper;

    public ConversationStateStep(ChatUserConversationMapper userConversationMapper) {
        super(20);
        this.userConversationMapper = userConversationMapper;
    }

    @Override
    public void apply(MessageSendContext ctx) {
        ChatUserConversationDoc userConversationDoc = userConversationMapper.selectOne(
                new LambdaQueryWrapper<ChatUserConversationDoc>()
                        .eq(ChatUserConversationDoc::getConversationId, ctx.getConversationId())
                        .eq(ChatUserConversationDoc::getUserId, Long.valueOf(ctx.getUserId())));
        if (userConversationDoc == null || Boolean.TRUE.equals(userConversationDoc.getDeleted())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不合法的会话状态");
        }

        ctx.setUserConversationDoc(userConversationDoc);
    }
}

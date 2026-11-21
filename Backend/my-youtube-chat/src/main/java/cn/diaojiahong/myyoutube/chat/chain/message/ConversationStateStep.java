package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.mapper.ConversationMemberMapper;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.domain.po.ConversationMember;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

@Component
public class ConversationStateStep extends MessageSendStep {

    private final ConversationMemberMapper conversationMemberMapper;

    public ConversationStateStep(ConversationMemberMapper conversationMemberMapper) {
        super(20);
        this.conversationMemberMapper = conversationMemberMapper;
    }

    @Override
    public void apply(MessageSendContext ctx) {
        ConversationMember member = conversationMemberMapper.selectOne(
                new LambdaQueryWrapper<ConversationMember>()
                        .eq(ConversationMember::getConversationId, ctx.getConversationId())
                        .eq(ConversationMember::getUserId, Long.valueOf(ctx.getUserId())));
        if (member == null || Boolean.TRUE.equals(member.getDeleted())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不合法的会话状态");
        }

        ctx.setMember(member);
    }
}

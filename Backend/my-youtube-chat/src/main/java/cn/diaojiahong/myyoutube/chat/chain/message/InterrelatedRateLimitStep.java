package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.chat.domain.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.domain.po.Conversation;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InterrelatedRateLimitStep extends MessageSendStep {

    private final FollowPort followPort;
    private final UserBlockPort blocks;

    public InterrelatedRateLimitStep(FollowPort followPort, UserBlockPort blocks) {
        super(100);
        this.followPort = followPort;
        this.blocks = blocks;
    }

    /** 检查私聊拉黑关系，并在空会话首发或等待回复的发起人发送时查询互关。 */
    @Override
    public void apply(MessageSendContext ctx) {
        Conversation conversation = ctx.getConversation();
        if (conversation.getType() != ConversationTypeEnum.SINGLE) {
            return;
        }
        Long senderId = Long.valueOf(ctx.getUserId());
        // 对端按 user_a/user_b 反推, 不依赖成员表里有对方的行
        Long peerId = conversation.peerOf(senderId);
        if (peerId == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不合法的会话状态");
        }
        if (blocks.blocksPrivateChat(senderId, peerId)) {
            throw new BizException(ErrorCode.FORBIDDEN, Conversation.PRIVATE_MESSAGE_UNAVAILABLE);
        }
        Long initiatorId = conversation.getCreatedBy();
        if (!conversation.requiresPrivateAdmission() || (initiatorId != null && !initiatorId.equals(senderId))) {
            return;
        }
        boolean interrelated = followPort.interrelated(
                new FollowPort.InterrelatedQuery(senderId, List.of(peerId)));
        ctx.setInterrelated(interrelated);
    }
}

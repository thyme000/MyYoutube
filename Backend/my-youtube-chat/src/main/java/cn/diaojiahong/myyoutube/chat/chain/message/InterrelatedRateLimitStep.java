package cn.diaojiahong.myyoutube.chat.chain.message;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.chat.mongo.enums.ConversationTypeEnum;
import cn.diaojiahong.myyoutube.chat.mongo.po.ChatConversationDoc;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class InterrelatedRateLimitStep extends MessageSendStep {

    private final FollowPort followPort;
    private final StringRedisTemplate redisTemplate;

    public InterrelatedRateLimitStep(FollowPort followPort, StringRedisTemplate redisTemplate) {
        super(100);
        this.followPort = followPort;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 私聊专属: 互关不限量, 非互关每日 10 条。判定"对方"时先把发送人从
     * 成员里摘掉、查完原样加回 —— 会话文档在写入阶段还要复用, 不能留下变更。
     */
    @Override
    public void apply(MessageSendContext ctx) {
        ChatConversationDoc conversation = ctx.getConversationDoc();
        if (!conversation.getType().equals(ConversationTypeEnum.SINGLE)) {
            return;
        }

        Long senderId = Long.valueOf(ctx.getUserId());
        conversation.getMemberIds().remove(senderId);
        try {
            boolean interrelated = followPort.interrelated(
                    new FollowPort.InterrelatedQuery(senderId, conversation.getMemberIds()));
            ctx.setInterrelated(interrelated);
            if (!interrelated) {
                Double times = redisTemplate.opsForZSet()
                        .score(RedisConstants.USER_CONVERSATION + ctx.getUserId(), ctx.getConversationId());
                if (times != null && times > 10) {
                    throw new BizException(ErrorCode.RATE_LIMITED, "你们暂时还不是互关关系哦,每天只能给对方发送10条消息");
                }
            }
        } finally {
            conversation.getMemberIds().add(senderId);
        }
    }
}

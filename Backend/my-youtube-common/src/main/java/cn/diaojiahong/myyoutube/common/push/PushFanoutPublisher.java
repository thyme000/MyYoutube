package cn.diaojiahong.myyoutube.common.push;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 推送扇出发布器: 业务侧推送一律发布到 Redis 频道, 各实例订阅后推给本地连接,
 * 解除"业务发生的实例"与"用户连接所在实例"的耦合。
 * 可靠性由持久层承担(通知落 MySQL/消息落 Mongo + 前端拉模式兜底), 丢广播只丢一次实时提示。
 */
@Component
@RequiredArgsConstructor
public class PushFanoutPublisher {

    private final StringRedisTemplate redisTemplate;

    /** 聊天 WS 报文: 报文本体原样下发前端 */
    public void pushWs(Long userId, Object payload) {
        PushEnvelope envelope = new PushEnvelope();
        envelope.setUserId(userId);
        envelope.setPayload(payload);
        redisTemplate.convertAndSend(RedisConstants.PUSH_CHANNEL_WS, JsonUtil.toJson(envelope));
    }

    /** SSE 门铃信号: 只发信号, 前端收到后拉取 */
    public void pushSseSignal(Long userId, String signal) {
        pushSseSignal(userId, signal, null);
    }

    /** SSE 门铃信号 + 业务载荷(如转码完成的 taskId), 前端可凭载荷直达 */
    public void pushSseSignal(Long userId, String signal, Object payload) {
        PushEnvelope envelope = new PushEnvelope();
        envelope.setUserId(userId);
        envelope.setSignal(signal);
        envelope.setPayload(payload);
        redisTemplate.convertAndSend(RedisConstants.PUSH_CHANNEL_SSE, JsonUtil.toJson(envelope));
    }
}

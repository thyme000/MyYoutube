package cn.diaojiahong.myyoutube.handler;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.push.PushEnvelope;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 推送扇出订阅端: 收到广播后推给本实例上的连接。
 * WS 频道 → 聊天 WS 报文本体; SSE 频道 → 门铃信号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalPushDispatcher implements MessageListener {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final MeterRegistry metrics;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(pattern, StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            PushEnvelope envelope = JsonUtil.fromJson(body, PushEnvelope.class);
            if (envelope == null || envelope.getUserId() == null) {
                return;
            }
            if (RedisConstants.PUSH_CHANNEL_WS.equals(channel)) {
                Object payload = envelope.getPayload();
                if (payload != null) {
                    // payload 为 String 时是已序列化的报文本体, 直接下发避免双重 JSON 编码
                    String json = payload instanceof String s ? s : JsonUtil.toJson(payload);
                    chatWebSocketHandler.sendToUser(envelope.getUserId(), json);
                }
            } else if (RedisConstants.PUSH_CHANNEL_SSE.equals(channel)) {
                if (envelope.getSignal() != null) {
                    Map<String, Object> sseBody = new java.util.HashMap<>();
                    sseBody.put("type", envelope.getSignal());
                    if (envelope.getPayload() != null) {
                        sseBody.put("payload", envelope.getPayload());
                    }
                    sseEmitterRegistry.sendToUser(envelope.getUserId(),
                            JsonUtil.toJson(sseBody));
                }
            }
        } catch (Exception e) {
            String kind = RedisConstants.PUSH_CHANNEL_WS.equals(channel) ? "ws"
                    : RedisConstants.PUSH_CHANNEL_SSE.equals(channel) ? "sse" : "other";
            metrics.counter("myyoutube.push.delivery.failures", "channel", kind).increment();
            log.warn("推送事件处理失败, channel={} errorType={}", kind, e.getClass().getSimpleName());
        }
    }
}

package cn.diaojiahong.myyoutube.handler;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.push.PushEnvelope;
import com.google.gson.GsonBuilder;
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

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(pattern, StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            PushEnvelope envelope = new GsonBuilder().create().fromJson(body, PushEnvelope.class);
            if (envelope == null || envelope.getUserId() == null) {
                return;
            }
            if (RedisConstants.PUSH_CHANNEL_WS.equals(channel)) {
                Object payload = envelope.getPayload();
                if (payload != null) {
                    // payload 为 String 时是已序列化的报文本体, 直接下发避免双重 JSON 编码
                    String json = payload instanceof String s ? s : new GsonBuilder().create().toJson(payload);
                    chatWebSocketHandler.sendToUser(envelope.getUserId(), json);
                }
            } else if (RedisConstants.PUSH_CHANNEL_SSE.equals(channel)) {
                if (envelope.getSignal() != null) {
                    sseEmitterRegistry.sendToUser(envelope.getUserId(),
                            new GsonBuilder().create().toJson(Map.of("type", envelope.getSignal())));
                }
            }
        } catch (Exception e) {
            log.warn("推送事件处理失败, channel={}, body={}", channel, body, e);
        }
    }
}

package cn.diaojiahong.myyoutube.handler;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.security.JwtPayload;
import cn.diaojiahong.myyoutube.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class NotifyWebSocketHandler extends TextWebSocketHandler {

    // userId -> 多个连接（多标签页）
    private static final Map<Long, Set<WebSocketSession>> USER_SESSIONS = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = resolveUserId(session); // 见下
        if (userId == null) {
            try { session.close(CloseStatus.NOT_ACCEPTABLE); } catch (Exception ignored) {}
            return;
        }
        session.getAttributes().put("userId", userId);
        USER_SESSIONS.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> set = USER_SESSIONS.get(userId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                USER_SESSIONS.remove(userId);
            }

        }
    }

    /** 给某个用户所有在线连接推 JSON 字符串 */
    public void sendToUser(Long userId, String json) {
        Set<WebSocketSession> set = USER_SESSIONS.get(userId);
        if (set == null) {
            return;
        }
        for (WebSocketSession s : set) {
            if (s.isOpen()) {
                try { s.sendMessage(new TextMessage(json)); } catch (Exception ignored) {}
            }
        }
    }

    private Long resolveUserId(WebSocketSession session) {
        String q = session.getUri() != null ? session.getUri().getQuery() : null;
        if (q == null || !q.startsWith("token=")) {
            return null;
        }
        JwtPayload payload = JwtUtil.verify(q.substring("token=".length()));
        if (payload == null) {
            return null;
        }
        String ver = redisTemplate.opsForValue().get(RedisConstants.USER_TOKEN_VER + payload.userId());
        if (ver != null && payload.issuedAtMillis() < Long.parseLong(ver)) {
            return null;
        }
        return payload.userId();
    }
}

package cn.diaojiahong.myyoutube.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天专用 WS 通道(纯下行, 未来承载"正在输入"等上行状态流)。
 * 连接生命周期由前端挂 in 聊天页: 进页连、离开断。
 * 报文经 Redis 扇出由 LocalPushDispatcher 推给本实例连接。
 */
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // userId -> 多个连接（多标签页）
    private static final Map<Long, Set<WebSocketSession>> USER_SESSIONS = new ConcurrentHashMap<>();
    private final ConnectionAuth connectionAuth;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = resolveUserId(session);
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

    /** 给本实例上该用户的所有连接推 JSON 字符串(跨实例由 Redis 扇出补齐) */
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
        if (q == null) {
            return null;
        }
        String token = UriComponentsBuilder.fromUriString("?" + q).build().getQueryParams().getFirst("token");
        return connectionAuth.resolveUserId(token);
    }
}

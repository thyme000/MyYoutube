package cn.diaojiahong.myyoutube.handler;

import cn.diaojiahong.myyoutube.common.utils.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 握手期取网关透传的 userId(UserIdHeaderFilter 已还原到 UserContext),
 * 存进 session attributes 供 afterConnectionEstablished 等非请求线程使用。
 */
@Component
public class UserHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String userId = UserContext.getUserId();
        if (userId == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put("userId", Long.valueOf(userId));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}

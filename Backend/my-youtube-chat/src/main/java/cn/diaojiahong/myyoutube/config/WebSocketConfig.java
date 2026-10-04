package cn.diaojiahong.myyoutube.config;

import cn.diaojiahong.myyoutube.handler.ChatWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final String[] allowedOrigins;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                           @Value("${my-youtube.ws.allowed-origins:*}") String[] allowedOrigins) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 前端进聊天页时连接: ws://host/ws/chat?token=xxx (通知类推送已迁至 SSE /sse/notify)
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOrigins(allowedOrigins);
    }
}

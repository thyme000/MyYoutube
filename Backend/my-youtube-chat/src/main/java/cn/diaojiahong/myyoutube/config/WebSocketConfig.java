package cn.diaojiahong.myyoutube.config;

import cn.diaojiahong.myyoutube.handler.NotifyWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final NotifyWebSocketHandler notifyWebSocketHandler;

    public WebSocketConfig(NotifyWebSocketHandler notifyWebSocketHandler) {
        this.notifyWebSocketHandler = notifyWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 前端连接：ws://host/ws/notify?token=xxx
        registry.addHandler(notifyWebSocketHandler, "/ws/notify")
                .setAllowedOrigins("*"); // 生产改成具体域名
    }
}

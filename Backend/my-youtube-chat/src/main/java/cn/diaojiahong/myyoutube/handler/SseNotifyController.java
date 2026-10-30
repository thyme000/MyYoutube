package cn.diaojiahong.myyoutube.handler;

import cn.diaojiahong.myyoutube.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * SSE 门铃端点: GET /sse/notify
 * 只推"有新通知"轻量信号, 前端收到后拉取详情/列表, 推送管道零业务字段。
 * 鉴权经网关完成(X-User-Id 透传), query 上不再携带 token。
 */
@RestController
@RequiredArgsConstructor
public class SseNotifyController {

    private final SseEmitterRegistry registry;

    @GetMapping(value = "/sse/notify", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(HttpServletResponse response) {
        String userIdStr = UserContext.getUserId();
        if (userIdStr == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        // 关闭代理层缓冲, 否则事件会被攒到连接关闭才吐出
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        SseEmitter emitter = new SseEmitter(0L);
        registry.register(Long.valueOf(userIdStr), emitter);
        try {
            emitter.send(SseEmitter.event().data("{\"type\":\"CONNECTED\"}"));
        } catch (Exception ignored) {
        }
        return emitter;
    }
}

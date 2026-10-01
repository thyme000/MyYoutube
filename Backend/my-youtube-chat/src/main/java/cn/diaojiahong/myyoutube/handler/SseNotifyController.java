package cn.diaojiahong.myyoutube.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * SSE 门铃端点: GET /sse/notify?token=xxx
 * 只推"有新通知"轻量信号, 前端收到后拉取详情/列表, 推送管道零业务字段。
 */
@RestController
@RequiredArgsConstructor
public class SseNotifyController {

    private final ConnectionAuth connectionAuth;
    private final SseEmitterRegistry registry;

    @GetMapping(value = "/sse/notify", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestParam("token") String token, HttpServletResponse response) {
        Long userId = connectionAuth.resolveUserId(token);
        if (userId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        // 关闭代理层缓冲, 否则事件会被攒到连接关闭才吐出
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        SseEmitter emitter = new SseEmitter(0L);
        registry.register(userId, emitter);
        try {
            emitter.send(SseEmitter.event().data("{\"type\":\"CONNECTED\"}"));
        } catch (Exception ignored) {}
        return emitter;
    }
}

package cn.diaojiahong.myyoutube.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE 门铃连接注册表(本实例): userId -> 多个 emitter(多标签页)。
 * 定时注入注释行心跳, 防止被 nginx proxy_read_timeout 等空闲超时掐断。
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    private static final long HEARTBEAT_INTERVAL_SECONDS = 25L;

    private final Map<Long, Set<SseEmitter>> USER_EMITTERS = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeatExecutor;

    @PostConstruct
    public void start() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::heartbeat, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        for (Set<SseEmitter> set : USER_EMITTERS.values()) {
            for (SseEmitter emitter : set) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
        USER_EMITTERS.clear();
    }

    public void register(Long userId, SseEmitter emitter) {
        Set<SseEmitter> set = USER_EMITTERS.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable remove = () -> {
            set.remove(emitter);
            if (set.isEmpty()) {
                USER_EMITTERS.remove(userId, set);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
    }

    /** 给本实例上该用户的所有连接发一条 data 事件(跨实例由 Redis 扇出补齐) */
    public void sendToUser(Long userId, String data) {
        Set<SseEmitter> set = USER_EMITTERS.get(userId);
        if (set == null) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().data(data));
            } catch (Exception e) {
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
    }

    private void heartbeat() {
        for (Set<SseEmitter> set : USER_EMITTERS.values()) {
            for (SseEmitter emitter : set) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    try { emitter.complete(); } catch (Exception ignored) {}
                }
            }
        }
    }
}

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
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(t -> remove(userId, emitter));
    }

    /** 给本实例上该用户的所有连接发一条 data 事件(跨实例由 Redis 扇出补齐) */
    public void sendToUser(Long userId, String data) {
        for (SseEmitter emitter : emittersOf(userId)) {
            try {
                emitter.send(SseEmitter.event().data(data));
            } catch (Exception e) {
                // 发送失败即视为连接已死: 先移除再 complete, complete 在 response 不可用时会再抛(见 heartbeat)
                remove(userId, emitter);
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
    }

    private void heartbeat() {
        // 外层兜底 catch: 单连接异常绝不能终止调度(scheduleAtFixedRate 任务抛异常会永久取消后续执行)
        try {
            for (Map.Entry<Long, Set<SseEmitter>> entry : USER_EMITTERS.entrySet()) {
                Long userId = entry.getKey();
                for (SseEmitter emitter : entry.getValue()) {
                    try {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } catch (Exception e) {
                        // 客户端已断开: 立即摘除, 避免死 emitter 每轮心跳重复报错
                        remove(userId, emitter);
                        try { emitter.complete(); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SSE 心跳轮次异常: {}", e.getMessage());
        }
    }

    private Set<SseEmitter> emittersOf(Long userId) {
        Set<SseEmitter> set = USER_EMITTERS.get(userId);
        return set == null ? Set.of() : set;
    }

    /** 从注册表摘除连接; 空集合顺手清掉, 重复调用安全 */
    private void remove(Long userId, SseEmitter emitter) {
        Set<SseEmitter> set = USER_EMITTERS.get(userId);
        if (set == null) {
            return;
        }
        set.remove(emitter);
        if (set.isEmpty()) {
            USER_EMITTERS.remove(userId, set);
        }
    }
}

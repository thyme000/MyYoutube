package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.transcode.task.MediaTaskExecutor;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MediaTaskWorker {
    private final MediaTaskService tasks;
    private final Map<String, MediaTaskExecutor> executors;
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1);
    public MediaTaskWorker(MediaTaskService tasks, List<MediaTaskExecutor> executors) {
        this.tasks = tasks;
        this.executors = executors.stream().collect(Collectors.toUnmodifiableMap(e -> e.type().name(), Function.identity()));
    }
    public void execute(MediaTaskMessage message) {
        var claimed = tasks.claim(message);
        if (claimed == null) return;
        var heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try { tasks.heartbeat(claimed.getTaskId(), claimed.getAttempt()); }
            catch (Exception e) { log.warn("Task heartbeat failed taskId={}", claimed.getTaskId(), e); }
        }, 30, 30, TimeUnit.SECONDS);
        String result = null, error = null;
        try { result = Objects.requireNonNull(executors.get(claimed.getTaskType()), "Unknown executor").execute(claimed); }
        catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Task execution failed pipelineId={} taskId={} attempt={}", claimed.getPipelineId(), claimed.getTaskId(), claimed.getAttempt(), e);
        }
        try { tasks.completeAndAdvance(claimed, result, error); }
        finally { heartbeat.cancel(false); }
    }
    @PreDestroy public void close() { heartbeats.shutdownNow(); }
}

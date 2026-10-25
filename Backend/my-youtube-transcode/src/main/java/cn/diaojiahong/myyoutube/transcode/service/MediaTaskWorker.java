package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.transcode.task.MediaTaskExecutor;
import cn.diaojiahong.myyoutube.common.observability.BusinessContext;
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
    private final Map<MediaTaskType, MediaTaskExecutor> executors;
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1);
    public MediaTaskWorker(MediaTaskService tasks, List<MediaTaskExecutor> executors) {
        this.tasks = tasks;
        this.executors = executors.stream().collect(Collectors.toUnmodifiableMap(MediaTaskExecutor::type, Function.identity()));
    }
    public void execute(MediaTaskMessage message) {
        var claimed = tasks.claim(message);
        if (claimed == null) return;
        var context = new HashMap<String, String>();
        context.put("batchId", Objects.toString(claimed.getRetryBatchId(), null));
        context.put("taskId", claimed.getTaskId().toString());
        context.put("taskType", claimed.getTaskType());
        context.put("attempt", claimed.getAttempt().toString());
        context.put("pipelineId", Objects.toString(claimed.getPipelineId(), null));
        context.put("traceId", org.slf4j.MDC.get("traceId"));
        if (!"DRAFT".equals(claimed.getPhase())) context.put("contentId", claimed.getOwnerId().toString());
        else context.put(MediaTaskType.AUDIO_PREPARE.matches(claimed.getTaskType()) ? "audioId"
                : MediaTaskType.PREPARE.matches(claimed.getTaskType()) ? "preparationId" : "draftId", claimed.getOwnerId().toString());
        var preparation = claimed.getInput() == null ? null : claimed.getInput().media().preparation();
        if (preparation != null) {
            context.put("draftId", preparation.draftId().toString());
            context.put("preparationId", preparation.preparationId().toString());
            context.put("mediaVersion", Long.toString(preparation.mediaVersion()));
        }
        try (var ignored = BusinessContext.open(context)) { executeClaimed(claimed, context); }
    }
    private void executeClaimed(MediaTaskMessage claimed, Map<String, String> context) {
        var heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try (var ignored = BusinessContext.open(context)) { tasks.heartbeat(claimed.getTaskId(), claimed.getAttempt()); }
            catch (Exception e) { log.warn("Task heartbeat failed taskId={}", claimed.getTaskId(), e); }
        }, 30, 30, TimeUnit.SECONDS);
        long startedAt = System.currentTimeMillis();
        String result = null, error = null, failedResource = null;
        InvalidMediaInputException rejected = null;
        ModelUnavailable deferred = null;
        try { result = Objects.requireNonNull(executors.get(MediaTaskType.fromCode(claimed.getTaskType())), "Unknown executor").execute(claimed); }
        catch (ModelUnavailable e) {
            deferred = e;
            log.info("Task model unavailable taskType={} taskId={} batchId={} attempt={} disposition={} resource={} reason={}",
                    claimed.getTaskType(), claimed.getTaskId(), claimed.getRetryBatchId(), claimed.getAttempt(),
                    e.terminal() ? "MANUAL_RETRY" : "DEFER", e.resource(), e.code());
        }
        catch (Exception e) {
            if (MediaTaskType.AUDIO_PREPARE.matches(claimed.getTaskType()) && e instanceof InvalidMediaInputException invalid) rejected = invalid;
            if (e instanceof cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure failure) failedResource = failure.resource();
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            // 唯一打印任务异常堆栈的位置: 重试/耗尽的判定在 completeAndAdvance 里只记结论, 不重复堆栈
            if (rejected != null) log.warn("Audio input rejected taskId={} attempt={} reason={}", claimed.getTaskId(), claimed.getAttempt(), rejected.reason());
            else log.warn("Task execution failed phase={} taskType={} pipelineId={} taskId={} attempt={} elapsedMs={}",
                    claimed.getPhase(), claimed.getTaskType(), claimed.getPipelineId(), claimed.getTaskId(),
                    claimed.getAttempt(), System.currentTimeMillis() - startedAt, e);
        }
        try {
            if (deferred != null) tasks.deferAndAdvance(claimed, deferred);
            else if (rejected != null) tasks.rejectAudioPreparation(claimed, rejected.reason());
            else if (failedResource != null) tasks.completeModelFailure(claimed, failedResource, error);
            else tasks.completeAndAdvance(claimed, result, error);
        }
        finally { heartbeat.cancel(false); }
        if (error == null && deferred == null) {
            log.info("Task completed phase={} taskType={} pipelineId={} taskId={} attempt={} elapsedMs={}",
                    claimed.getPhase(), claimed.getTaskType(), claimed.getPipelineId(), claimed.getTaskId(),
                    claimed.getAttempt(), System.currentTimeMillis() - startedAt);
        }
    }
    @PreDestroy public void close() { heartbeats.shutdownNow(); }
}

package cn.diaojiahong.myyoutube.search.recall;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RecallPipeline {
    private final List<RecallStrategy> strategies;
    private final RrfFuser rrfFuser;
    private final LatestFuser latestFuser;
    private final RecallProperties properties;
    private final Map<String, ThreadPoolTaskExecutor> executors;
    private final MeterRegistry metrics;

    public RecallPipeline(List<RecallStrategy> strategies, RrfFuser rrfFuser, LatestFuser latestFuser,
                          RecallProperties properties, MeterRegistry metrics,
                          Map<String, ThreadPoolTaskExecutor> executors) {
        this.strategies = strategies;
        this.rrfFuser = rrfFuser;
        this.latestFuser = latestFuser;
        this.properties = properties;
        this.metrics = metrics;
        this.executors = executors;
    }

    public List<String> execute(SearchContext ctx) {
        long waitDeadline = ctx.getStartedNanos() + TimeUnit.MILLISECONDS.toNanos(properties.getWaitMillis());
        long deadline = ctx.getStartedNanos() + TimeUnit.MILLISECONDS.toNanos(properties.getTimeoutMillis());
        List<RecallStrategy> active = strategies.stream().filter(strategy -> strategy.shouldRun(ctx)).toList();
        BlockingQueue<FutureTask<RecallResult>> completed = new LinkedBlockingQueue<>();
        List<RunningRecall> tasks = new ArrayList<>();
        List<List<String>> recalls = new ArrayList<>(Collections.nCopies(active.size(), List.of()));
        try {
            for (int i = 0; i < active.size(); i++) {
                RecallStrategy strategy = active.get(i);
                var executor = executors.get(strategy.executorName());
                if (executor == null) throw new IllegalStateException("未配置召回执行器: " + strategy.executorName());
                int index = i;
                var task = new FutureTask<>(() -> new RecallResult(index, recallQuietly(strategy, ctx, deadline))) {
                    @Override protected void done() { completed.add(this); }
                };
                tasks.add(new RunningRecall(executor, task));
                try { executor.execute(task); }
                catch (RejectedExecutionException error) {
                    task.cancel(false);
                    metrics.counter("myyoutube.search.recall.rejected", "strategy", strategy.getClass().getSimpleName()).increment();
                }
            }
            boolean hasCandidates = false;
            int pending = tasks.size();
            while (pending > 0) {
                var task = completed.poll();
                if (task == null) {
                    long now = System.nanoTime();
                    if (now >= deadline || (now >= waitDeadline && hasCandidates)) break;
                    long until = now < waitDeadline ? waitDeadline : deadline;
                    task = completed.poll(until - now, TimeUnit.NANOSECONDS);
                    if (task == null) continue;
                }
                pending--;
                try {
                    RecallResult result = task.get();
                    recalls.set(result.index(), result.ids());
                    hasCandidates |= !result.ids().isEmpty();
                } catch (CancellationException ignored) { }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("搜索等待已中断", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("搜索召回失败", error.getCause());
        } finally {
            // 返回时取消不再需要的慢路；异常或中断退出时同样取消其余任务。
            // 移除排队任务，避免请求结束后继续占用执行资源。
            for (RunningRecall task : tasks) {
                task.future().cancel(true);
                task.executor().getThreadPoolExecutor().remove(task.future());
            }
        }
        return ctx.isSortByLatest() ? latestFuser.fuse(ctx, recalls) : rrfFuser.fuse(ctx, recalls);
    }

    private List<String> recallQuietly(RecallStrategy strategy, SearchContext ctx, long deadline) {
        try {
            if (System.nanoTime() >= deadline) return List.of();
            return strategy.recall(ctx, deadline);
        }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            metrics.counter("myyoutube.search.recall.failures", "strategy", strategy.getClass().getSimpleName()).increment();
            log.warn("{} 召回失败, 该路降级: {}", strategy.getClass().getSimpleName(), error.getMessage());
            return List.of();
        }
    }

    private record RecallResult(int index, List<String> ids) {}
    private record RunningRecall(ThreadPoolTaskExecutor executor, FutureTask<RecallResult> future) {}
}

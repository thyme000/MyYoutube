package cn.diaojiahong.myyoutube.recommend.cf;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;

/**
 * 相似度计算专用线程池。CPU 密集, 线程数 = 核数。
 * 任务列表调用方先按工作量降序排, worker 用游标自取(长任务先领), 天然负载均衡;
 * 各 worker 攒局部结果, 结束后由 merger 归约, 热路径全程无锁。
 */
@Component
public class SimComputePool {

    /** 小于该任务数直接主线程串行, 免调度与合并开销 */
    private static final int SERIAL_THRESHOLD = 128;

    private final ExecutorService executor;

    public SimComputePool() {
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
            Thread t = new Thread(r, "cf-sim");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 分片并行: worker 各自 localInit 建局部结果, 逐任务 step 累积, 全部完成后两两 merger 归约。
     * 任务数不足阈值时在调用线程串行执行(不走 merger, 结果等价)。
     *
     * @param sortedTasks 已按工作量降序的任务列表
     */
    public <T, R> R collectAndMerge(List<T> sortedTasks,
                                    Supplier<R> localInit,
                                    BiConsumer<R, T> step,
                                    BinaryOperator<R> merger) {
        if (sortedTasks.size() < SERIAL_THRESHOLD) {
            R local = localInit.get();
            sortedTasks.forEach(t -> step.accept(local, t));
            return local;
        }
        AtomicInteger cursor = new AtomicInteger();
        int workers = Runtime.getRuntime().availableProcessors();
        List<Callable<R>> jobs = new ArrayList<>(workers);
        for (int w = 0; w < workers; w++) {
            jobs.add(() -> {
                R local = localInit.get();
                int i;
                while ((i = cursor.getAndIncrement()) < sortedTasks.size()) {
                    step.accept(local, sortedTasks.get(i));
                }
                return local;
            });
        }
        List<R> locals;
        try {
            List<Future<R>> futures = executor.invokeAll(jobs);
            locals = new ArrayList<>(futures.size());
            for (Future<R> f : futures) {
                locals.add(f.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("相似度并行计算被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("相似度并行计算失败", e.getCause());
        }
        R merged = locals.get(0);
        for (int i = 1; i < locals.size(); i++) {
            merged = merger.apply(merged, locals.get(i));
        }
        return merged;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}

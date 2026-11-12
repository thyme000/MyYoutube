package cn.diaojiahong.myyoutube.common.counter;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 计数落库轮与对账轮。正确性由 {@link CounterBuffer#claim} 的 Lua 原子认领保证，
 * 锁只用来避免两个实例同时做无用功（见 docs/计数落库改造设计.md §6）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterFlushService {

    /**
     * 不同 id 的落库(user, video)互不依赖，并行只缩短后台补写时间。
     * 线程数要小于连接池大小，否则会阻塞连接池。
     */
    static final int FLUSH_THREADS = 4;

    private final CounterBuffer counterBuffer;
    private final ObjectProvider<CounterTarget> targets;
    private final ExecutorService flushExecutor = newFlushExecutor();

    /** 落库轮：回补 inflight + 认领 pending + 落库 + ack。单个 id 失败只跳过该 id，不 ack，留给下轮重放 */
    public void flushRound() {
        List<CounterTarget> targetList = targets.stream().toList();
        for (CounterTarget target : targetList) {
            try {
                flushTarget(target);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.warn("计数落库被中断, 剩余类型下轮处理, type={}", target.type());
                return;
            }
        }
    }

    /** 对账轮：用 COUNT(*) 无条件覆盖，抹平任意方向的累积误差 */
    public void reconcileRound() {
        for (CounterTarget target : targets.stream().toList()) {
            if (!target.type().reconcile()) {
                continue;
            }
            for (Long id : counterBuffer.dirtyIds(target.type())) {
                reconcileOne(target, id);
            }
        }
    }

    /**
     * 管理端按指定 id 立刻覆盖。和小时对账同一条写路径：先丢掉 Redis 里尚未落库的增量，
     * 再用权威值覆盖。不扫 dirty 集合，因此分享数这类不进小时对账的类型也能修。
     *
     * @return 实际执行了覆盖的条数。没有权威值的 id 只清 dirty，不计入
     */
    public int repair(CounterType type, List<Long> ids) {
        List<Long> requested = repairIds(ids);
        CounterTarget target = find(type);
        int repaired = 0;
        for (Long id : requested) {
            if (applyTruth(target, id)) {
                repaired++;
            }
        }
        return repaired;
    }

    private void flushTarget(CounterTarget target) throws InterruptedException {
        CounterType type = target.type();
        // inflight 排前面：回补属于正确性，不能被本轮 pending 的量顶掉
        Set<Long> ids = new LinkedHashSet<>(counterBuffer.scanIds(CounterKeys.inflightPrefix(type)));
        ids.addAll(counterBuffer.scanIds(CounterKeys.pendingPrefix(type)));
        List<Long> batch = new ArrayList<>();
        for (Long id : ids) {
            if (batch.size() >= RedisConstants.COUNTER_MAX_KEYS_PER_ROUND) {
                log.warn("计数落库单轮达上限, 剩余 id 下轮处理, type={}, limit={}", type, RedisConstants.COUNTER_MAX_KEYS_PER_ROUND);
                break;
            }
            batch.add(id);
        }

        if (batch.size() <= 1) {
            for (Long id : batch) {
                flushOne(target, id);
            }
        } else {
            flushInParallel(target, batch);
        }
    }

    private void flushInParallel(CounterTarget target, List<Long> batch) throws InterruptedException {
        AtomicInteger cursor = new AtomicInteger();
        List<Callable<Void>> jobs = new ArrayList<>(FLUSH_THREADS);
        for (int worker = 0; worker < FLUSH_THREADS; worker++) {
            jobs.add(() -> {
                int index;
                while ((index = cursor.getAndIncrement()) < batch.size()) {
                    flushOne(target, batch.get(index));
                }
                return null;
            });
        }
        List<Future<Void>> finished;
        try {
            finished = flushExecutor.invokeAll(jobs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        // 任务此时都已结束。逐个取异常，一条失败只记一行，不盖住其余 future
        for (Future<Void> future : finished) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (ExecutionException failure) {
                log.warn("计数落库并行失败, type={}", target.type(), failure.getCause());
            }
        }
        // 任务赶在等待之前跑完时，invokeAll 不会抛，标志位还在就停轮
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private void flushOne(CounterTarget target, long id) {
        CounterType type = target.type();
        Long delta;
        try {
            delta = counterBuffer.claim(type, id);
        } catch (Exception e) {
            log.warn("计数认领失败, 下轮重试, type={}, id={}", type, id, e);
            return;
        }
        if (delta == null) {
            return;
        }
        try {
            target.applyDelta(id, delta);
        } catch (Exception e) {
            log.warn("计数落库失败, 下轮回补重放, type={}, id={}, delta={}", type, id, delta, e);
            return;
        }
        try {
            counterBuffer.ack(type, id);
        } catch (Exception e) {
            log.warn("计数确认失败, 下轮回补重放, type={}, id={}, delta={}", type, id, delta, e);
            return;
        }
    }

    @PreDestroy
    void shutdownFlushExecutor() {
        flushExecutor.shutdownNow();
    }

    private static ExecutorService newFlushExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        // 每次只提交 FLUSH_THREADS 个任务，并等它们结束再交下一批。
        // 队列只交接这一批；再多交就拒绝，避免调度线程自己跑落库、多占一条数据库连接。
        // core 等于 max，空闲时间不会回收这些线程。
        return new ThreadPoolExecutor(
                FLUSH_THREADS,
                FLUSH_THREADS,
                0L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(FLUSH_THREADS),
                runnable -> {
                    Thread thread = new Thread(runnable, "counter-flush-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void reconcileOne(CounterTarget target, long id) {
        try {
            applyTruth(target, id);
        } catch (Exception e) {
            log.warn("计数对账失败, 下轮重试, type={}, id={}", target.type(), id, e);
        }
    }

    /** @return 是否用权威值覆盖了计数列 */
    private boolean applyTruth(CounterTarget target, long id) {
        CounterType type = target.type();
        // 先丢弃 pending：权威值来自同步写入的关系表, 已经含了这部分增量
        counterBuffer.discard(type, id);
        Long truth = target.authoritativeValue(id);
        if (truth == null) {
            counterBuffer.clearDirty(type, id);
            return false;
        }
        target.overwrite(id, truth);
        counterBuffer.clearDirty(type, id);
        return true;
    }

    private CounterTarget find(CounterType type) {
        for (CounterTarget target : targets.stream().toList()) {
            if (target.type() == type) {
                return target;
            }
        }
        throw new BizException(ErrorCode.INVALID_ARGUMENT, "本服务不处理该计数类型");
    }

    private static List<Long> repairIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请选择要覆盖的计数");
        }
        if (ids.size() > PageQuery.MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "单次最多覆盖 " + PageQuery.MAX_PAGE_SIZE + " 条");
        }

        List<Long> distinct = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id < 1) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "计数 id 无效");
            }
            if (!distinct.contains(id)) {
                distinct.add(id);
            }
        }
        return distinct;
    }
}

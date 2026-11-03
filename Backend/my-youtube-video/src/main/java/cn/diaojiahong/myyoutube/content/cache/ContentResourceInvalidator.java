package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.concurrent.Semaphore;

/**
 * 提交后立即删除，并在配置延迟后尽力再删一次，不保证提交后固定时限内一致。
 * 第二次删除只清理其实际执行前完成的旧值回填；之后的回填由写入时起算的 TTL 兜底。
 * 进程重启或删除、调度失败也依赖 TTL。
 */
@Slf4j
@Component
public class ContentResourceInvalidator {
    // 每实例最多保留约千个延迟任务，限制 Redis 变慢时的积压；未经压测定容。
    // 超限仍执行立即删除，放弃第二次删除，由 TTL 兜底。
    private static final int MAX_DELAYED_EVICTIONS = 1024;

    private final ContentResourceCache cache;
    private final TaskScheduler scheduler;
    private final ContentResourceCacheProperties properties;
    private final MeterRegistry metrics;
    private final Semaphore delayedTasks = new Semaphore(MAX_DELAYED_EVICTIONS);

    public ContentResourceInvalidator(ContentResourceCache cache,
            @Qualifier(ContentResourceCacheConfiguration.EVICTION_SCHEDULER) TaskScheduler scheduler,
            ContentResourceCacheProperties properties, MeterRegistry metrics) {
        this.cache = cache;
        this.scheduler = scheduler;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void evictAfterCommit(ContentResourceRegion region, String resourceId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictTwice(region, resourceId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictTwice(region, resourceId);
            }
        });
    }

    private void evictTwice(ContentResourceRegion region, String resourceId) {
        cache.evict(region, resourceId);
        if (!delayedTasks.tryAcquire()) {
            dropped(DropReason.QUEUE_FULL);
            log.warn("Content cache delayed eviction queue full region={} id={}", region, resourceId);
            return;
        }

        try {
            scheduler.schedule(() -> {
                try {
                    cache.evict(region, resourceId);
                } finally {
                    delayedTasks.release();
                }
            }, Instant.now().plusMillis(properties.getDoubleDeleteDelayMillis()));
        } catch (RuntimeException unavailable) {
            delayedTasks.release();
            dropped(DropReason.SCHEDULING_FAILED);
            log.warn("Content cache delayed eviction scheduling failed region={} id={}", region, resourceId, unavailable);
        }
    }

    private void dropped(DropReason reason) {
        try {
            metrics.counter(MetricNames.CONTENT_CACHE_DELAYED_EVICT_DROPPED, "reason", reason.name()).increment();
        } catch (RuntimeException metricsUnavailable) {
            /* 指标不可用不影响已提交的业务操作。 */
        }
    }

    private enum DropReason { QUEUE_FULL, SCHEDULING_FAILED }
}

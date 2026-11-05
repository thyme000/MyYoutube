package cn.diaojiahong.myyoutube.content.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class ContentResourceCacheConfiguration {
    public static final String EVICTION_SCHEDULER = "contentCacheEvictionScheduler";
    public static final String WARMUP_EXECUTOR = "contentCacheWarmupExecutor";
    public static final String WARMUP_LOADER_EXECUTOR = "contentCacheWarmupLoaderExecutor";
    private static final int WARMUP_THREADS = 2;
    private static final int WARMUP_QUEUE_CAPACITY = 128;
    private static final int WARMUP_LOADER_THREADS = 4;

    @Bean(name = EVICTION_SCHEDULER, defaultCandidate = false)
    public TaskScheduler contentCacheEvictionScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("content-cache-eviction-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean(name = WARMUP_EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor contentCacheWarmupExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(WARMUP_THREADS);
        executor.setMaxPoolSize(WARMUP_THREADS);
        executor.setQueueCapacity(WARMUP_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("content-cache-warmup-");
        executor.setDaemon(true);
        // 预热排队满时跳过，不能退回发布线程执行数据库或对象存储读取。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    @Bean(name = WARMUP_LOADER_EXECUTOR, defaultCandidate = false)
    public ThreadPoolTaskExecutor contentCacheWarmupLoaderExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(WARMUP_LOADER_THREADS);
        executor.setMaxPoolSize(WARMUP_LOADER_THREADS);
        executor.setQueueCapacity(WARMUP_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("content-cache-warmup-load-");
        executor.setDaemon(true);
        // 父预热任务会等待子加载任务；使用独立有界线程池，队列满时拒绝，不在调用线程执行。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}

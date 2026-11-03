package cn.diaojiahong.myyoutube.content.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class ContentResourceCacheConfiguration {
    public static final String EVICTION_SCHEDULER = "contentCacheEvictionScheduler";

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
}

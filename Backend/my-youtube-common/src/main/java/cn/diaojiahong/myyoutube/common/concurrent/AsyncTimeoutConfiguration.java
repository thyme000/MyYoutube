package cn.diaojiahong.myyoutube.common.concurrent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class AsyncTimeoutConfiguration {

    // 只供异步等待计时使用，不参与业务 @Scheduled 默认调度器的选择。
    // 以计时接口暴露，避免启动失败时，子容器关闭事件在销毁阶段反向创建尚未初始化的监听器。
    @Bean(name = "asyncTimeoutScheduler", defaultCandidate = false)
    public TaskScheduler asyncTimeoutScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("async-timeout-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}

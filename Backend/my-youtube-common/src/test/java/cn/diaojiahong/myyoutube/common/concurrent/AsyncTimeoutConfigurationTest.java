package cn.diaojiahong.myyoutube.common.concurrent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.*;

class AsyncTimeoutConfigurationTest {
    @Test void timeoutSchedulerDoesNotReplaceBusinessSchedulingAndClosesWithTheContext() {
        ThreadPoolTaskScheduler[] timer = new ThreadPoolTaskScheduler[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(AsyncTimeoutConfiguration.class, Scheduling.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    timer[0] = context.getBean("asyncTimeoutScheduler", ThreadPoolTaskScheduler.class);
                    assertNotSame(timer[0], context.getBean("taskScheduler"));
                    assertTrue(timer[0].isRunning());
                });

        assertTrue(timer[0].getScheduledThreadPoolExecutor().isShutdown());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {}
}

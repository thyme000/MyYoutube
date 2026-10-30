package cn.diaojiahong.myyoutube.common.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncTimeoutConfigurationTest {
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedStartupDoesNotCreateTheTimerDuringChildContextDestruction(CapturedOutput output) {
        new ApplicationContextRunner()
                .withUserConfiguration(FailingStartup.class, AsyncTimeoutConfiguration.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("original startup failure");
                    assertThat(output.getAll()).doesNotContain("BeanCreationNotAllowedException");
                });
    }

    @Test
    void timeoutSchedulerDoesNotReplaceBusinessSchedulingAndClosesWithTheContext() {
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
    static class Scheduling {
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingStartup {
        @Bean
        GenericApplicationContext childContext(ApplicationContext parent) {
            GenericApplicationContext child = new GenericApplicationContext();
            child.setParent(parent);
            child.refresh();
            return child;
        }

        @Bean
        Object brokenBean(GenericApplicationContext childContext) {
            throw new IllegalStateException("original startup failure");
        }
    }
}

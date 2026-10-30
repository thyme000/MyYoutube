package cn.diaojiahong.myyoutube.common.idempotent;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class IdempotentConfigurationTest {
    @Test
    void leaseSchedulerDoesNotReplaceBusinessSchedulingBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, IdempotentAutoConfiguration.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(Work.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(IdempotentGuard.class));
                    assertNotNull(context.getBean(IdempotentAspect.class));
                    assertTrue(AopUtils.isAopProxy(context.getBean(Work.class)));
                    assertTrue(context.getBeansOfType(TaskScheduler.class).isEmpty());
                    assertTrue(context.getBeansOfType(ScheduledExecutorService.class).isEmpty());
                });
    }

    static class Work {
        @Idempotent(key = "'context:test'", ttl = 0)
        public void run() {
        }
    }
}

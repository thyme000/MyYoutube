package cn.diaojiahong.myyoutube.content.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentResourceCacheConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import({ContentResourceInvalidator.class, ContentResourceCacheProperties.class})
    static class Config {
        @Bean
        ContentResourceCache cache() {
            return mock(ContentResourceCache.class);
        }

        @Bean
        MeterRegistry metrics() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    @Timeout(10)
    void delayedEvictionUsesItsOwnSchedulerAndPreservesTheDefaultScheduler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class,
                        TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(Config.class, ContentResourceCacheConfiguration.class)
                .withPropertyValues("my-youtube.content-resource-cache.double-delete-delay-millis=10")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    TaskScheduler dedicated = application.getBean(ContentResourceCacheConfiguration.EVICTION_SCHEDULER,
                            TaskScheduler.class);
                    assertNotSame(dedicated, application.getBean(TaskScheduler.class));

                    ContentResourceCache cache = application.getBean(ContentResourceCache.class);
                    var deletions = new CountDownLatch(2);
                    var lastThread = new AtomicReference<String>();
                    doAnswer(call -> {
                        lastThread.set(Thread.currentThread().getName());
                        deletions.countDown();
                        return null;
                    }).when(cache).evict(ContentResourceRegion.CONTENT_STATE, "1");
                    application.getBean(ContentResourceInvalidator.class)
                            .evictAfterCommit(ContentResourceRegion.CONTENT_STATE, "1");
                    assertTrue(deletions.await(2, TimeUnit.SECONDS));
                    assertTrue(lastThread.get().startsWith("content-cache-eviction-"));
                    verify(cache, times(2)).evict(ContentResourceRegion.CONTENT_STATE, "1");
                });
    }
}

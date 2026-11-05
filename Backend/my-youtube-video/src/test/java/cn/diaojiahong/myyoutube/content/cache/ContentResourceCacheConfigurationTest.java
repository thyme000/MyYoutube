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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    @Timeout(10)
    void warmupHasABoundedExecutorAndNeverFallsBackToTheCallingThread() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class,
                        TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(Config.class, ContentResourceCacheConfiguration.class)
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    ThreadPoolTaskExecutor executor = application.getBean(
                            ContentResourceCacheConfiguration.WARMUP_EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertNotSame(executor, application.getBean("applicationTaskExecutor", Executor.class));
                    var running = new CountDownLatch(2);
                    var release = new CountDownLatch(1);
                    var calledInline = new AtomicBoolean();
                    var workerThread = new AtomicReference<String>();
                    Runnable block = () -> {
                        workerThread.set(Thread.currentThread().getName());
                        running.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    };
                    try {
                        executor.execute(block);
                        executor.execute(block);
                        assertTrue(running.await(2, TimeUnit.SECONDS));
                        assertTrue(workerThread.get().startsWith("content-cache-warmup-"));
                        int capacity = executor.getThreadPoolExecutor().getQueue().remainingCapacity();
                        assertEquals(128, capacity);
                        for (int index = 0; index < capacity; index++) {
                            executor.execute(() -> {});
                        }
                        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> calledInline.set(true)));
                        assertFalse(calledInline.get());
                    } finally {
                        release.countDown();
                    }
                });
    }

    @Test
    @Timeout(10)
    void loadersCanRunWhileAllParentWarmupThreadsAreOccupied() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class,
                        TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(Config.class, ContentResourceCacheConfiguration.class)
                .run(application -> {
                    ThreadPoolTaskExecutor parent = application.getBean(
                            ContentResourceCacheConfiguration.WARMUP_EXECUTOR, ThreadPoolTaskExecutor.class);
                    ThreadPoolTaskExecutor loader = application.getBean(
                            ContentResourceCacheConfiguration.WARMUP_LOADER_EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertNotSame(parent, loader);
                    assertNotSame(loader, application.getBean("applicationTaskExecutor", Executor.class));
                    assertEquals(4, loader.getMaxPoolSize());
                    assertEquals(128, loader.getQueueCapacity());
                    var parentsRunning = new CountDownLatch(2);
                    var release = new CountDownLatch(1);
                    var loaded = new CountDownLatch(1);
                    var loaderThread = new AtomicReference<String>();
                    Runnable block = () -> {
                        parentsRunning.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    };
                    try {
                        parent.execute(block);
                        parent.execute(block);
                        assertTrue(parentsRunning.await(2, TimeUnit.SECONDS));
                        loader.execute(() -> {
                            loaderThread.set(Thread.currentThread().getName());
                            loaded.countDown();
                        });
                        assertTrue(loaded.await(2, TimeUnit.SECONDS));
                        assertTrue(loaderThread.get().startsWith("content-cache-warmup-load-"));
                    } finally {
                        release.countDown();
                    }
                });
    }
}

package cn.diaojiahong.myyoutube.common.concurrent;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class AsyncTimeoutAspectTest {
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private final ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) new AsyncTimeoutConfiguration().asyncTimeoutScheduler();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AsyncTimeoutAspect aspect;
    private final Service service;

    AsyncTimeoutAspectTest() {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        scheduler.initialize();

        AsyncTimeoutProperties properties = new AsyncTimeoutProperties();
        AsyncTimeoutProperties.Policy policy = new AsyncTimeoutProperties.Policy();
        policy.setExecutor("worker"); policy.setTimeoutMillis(150);
        properties.getResources().put("test", policy);
        properties.validate();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("worker", executor);
        aspect = new AsyncTimeoutAspect(properties, beans, metrics, scheduler);
        AspectJProxyFactory proxy = new AspectJProxyFactory(new Service());
        proxy.addAspect(aspect);
        service = proxy.getProxy();
    }

    @AfterEach void close() { executor.shutdown(); scheduler.shutdown(); metrics.close(); }

    @Test void genericResultsRunOnTheConfiguredExecutor() throws Exception {
        Thread caller = Thread.currentThread();
        assertNotSame(caller, service.thread().get());
        assertEquals(42, service.number().get());
        assertEquals("ok", service.text().get());
    }

    @Test void timeoutDoesNotInterruptRunningWorkOrReplaceItsPublicResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CompletableFuture<String> response = service.block(entered, release, interrupted);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, assertThrows(ExecutionException.class, response::get).getCause());
        assertFalse(interrupted.get());
        release.countDown();
        executor.submit(() -> {}).get(1, TimeUnit.SECONDS);
        assertInstanceOf(TimeoutException.class, assertThrows(ExecutionException.class, response::get).getCause());
        assertEquals(1, metrics.counter(MetricNames.ASYNC_TIMEOUTS, "resource", "test").count());
    }

    @Test void queueWaitCountsAndExpiredQueuedWorkDoesNotStart() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> { try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
        AtomicBoolean called = new AtomicBoolean();
        CompletableFuture<Boolean> response = service.mark(called);
        assertInstanceOf(TimeoutException.class, assertThrows(ExecutionException.class, response::get).getCause());
        release.countDown();
        executor.shutdown();
        assertTrue(executor.getThreadPoolExecutor().awaitTermination(1, TimeUnit.SECONDS));
        assertFalse(called.get());
    }

    @Test void saturatedExecutorRejectsWithoutRunningOnCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Runnable block = () -> { try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } };
        executor.execute(block);
        executor.execute(block);
        AtomicBoolean called = new AtomicBoolean();
        try {
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> service.mark(called).get()).getCause());
            assertFalse(called.get());
        } finally { release.countDown(); }
    }

    @Test void originalFailuresAreNotConvertedIntoEmptyResults() {
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(ExecutionException.class, () -> service.fail().get()).getCause());
        assertThrows(IllegalStateException.class, service::invalidSignature);
    }

    public static class Service {
        @AsyncTimeout("test") public CompletableFuture<Thread> thread() { return CompletableFuture.completedFuture(Thread.currentThread()); }
        @AsyncTimeout("test") public CompletableFuture<Integer> number() { return CompletableFuture.completedFuture(42); }
        @AsyncTimeout("test") public CompletableFuture<String> text() { return CompletableFuture.completedFuture("ok"); }
        @AsyncTimeout("test") public CompletableFuture<String> fail() { throw new IllegalArgumentException("bug"); }
        @AsyncTimeout("test") public String invalidSignature() { return "invalid"; }
        @AsyncTimeout("test") public CompletableFuture<Boolean> mark(AtomicBoolean called) { called.set(true); return CompletableFuture.completedFuture(true); }
        @AsyncTimeout("test")
        public CompletableFuture<String> block(CountDownLatch entered, CountDownLatch release, AtomicBoolean interrupted) {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException error) { interrupted.set(true); Thread.currentThread().interrupt(); }
            return CompletableFuture.completedFuture("late");
        }
    }
}

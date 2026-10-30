package cn.diaojiahong.myyoutube.common.idempotent;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "idempotent.redis.port", matches = "[0-9]+")
class LiveIdempotentGuardTest {
    final String key = "guard-it:" + UUID.randomUUID();
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    ThreadPoolTaskScheduler scheduler;
    IdempotentGuard guard;

    final List<String> extraKeys = new ArrayList<>();

    @BeforeEach
    void setup() {
        connection = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("idempotent.redis.port"));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        guard = new IdempotentGuard(redis, scheduler);
    }

    @AfterEach
    void cleanup() {
        scheduler.shutdown();
        redis.delete(RedisConstants.IDEMPOTENT + key);
        if (!extraKeys.isEmpty()) redis.delete(extraKeys);
        connection.destroy();
    }

    @Test
    void twoInstancesAllowOnlyOneOwnerAndCacheCompletedResult() throws Exception {
        var second = new IdempotentGuard(redis, scheduler);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<Object> first = pool.submit(() -> {
                try (IdempotentGuard.Lease lease = guard.acquire(key, 1)) {
                    acquired.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    lease.complete(IdempotentGuard.DONE, 10);
                }
                return null;
            });
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            assertThrows(BizException.class, () -> second.acquire(key, 1));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            try (IdempotentGuard.Lease duplicate = second.acquire(key, 1)) {
                assertFalse(duplicate.acquired());
                assertEquals(IdempotentGuard.DONE, duplicate.cachedValue());
            }
            assertTrue(redis.getExpire(RedisConstants.IDEMPOTENT + key, TimeUnit.MILLISECONDS) > 8000);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void autoRenewalKeepsOccupancyPastInitialLease() throws Exception {
        try (IdempotentGuard.Lease lease = guard.acquire(key, 1)) {
            Thread.sleep(1600);
            assertThrows(BizException.class, () -> guard.acquire(key, 1));
            lease.complete(IdempotentGuard.DONE, 0);
        }
        assertNull(redis.opsForValue().get(RedisConstants.IDEMPOTENT + key));
        assertEquals(0, scheduler.getScheduledThreadPoolExecutor().getQueue().size());
    }

    @Test
    void failedExecutionReleasesAndCanBeRetried() {
        assertThrows(IllegalStateException.class, () -> {
            try (IdempotentGuard.Lease lease = guard.acquire(key, 1)) {
                assertTrue(lease.acquired());
                throw new IllegalStateException("failed");
            }
        });
        try (IdempotentGuard.Lease retry = guard.acquire(key, 1)) {
            assertTrue(retry.acquired());
        }
    }

    @Test
    void expiredOwnerCannotCompleteOrDeleteNewOwnersOccupancy() throws Exception {
        ThreadPoolTaskScheduler paused = mock(ThreadPoolTaskScheduler.class);
        when(paused.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));
        IdempotentGuard.Lease old = new IdempotentGuard(redis, paused).acquire(key, 1);
        try {
            Thread.sleep(1100);
            try (IdempotentGuard.Lease current = guard.acquire(key, 2)) {
                String token = redis.opsForValue().get(RedisConstants.IDEMPOTENT + key);
                assertThrows(IllegalStateException.class, () -> old.complete(IdempotentGuard.DONE, 20));
                old.close();
                assertEquals(token, redis.opsForValue().get(RedisConstants.IDEMPOTENT + key));
                current.complete(IdempotentGuard.DONE, 20);
            }
        } finally {
            old.close();
        }
    }

    @Test
    void lostOwnershipFailsChecksAndCancelsRenewal() {
        try (IdempotentGuard.Lease lease = guard.acquire(key, 1)) {
            redis.opsForValue().set(RedisConstants.IDEMPOTENT + key, "RUNNING:replacement", Duration.ofSeconds(10));
            assertThrows(IllegalStateException.class, lease::assertOwned);
            assertThrows(IllegalStateException.class, () -> lease.complete(IdempotentGuard.DONE, 10));
        }
        assertEquals("RUNNING:replacement", redis.opsForValue().get(RedisConstants.IDEMPOTENT + key));
        assertEquals(0, scheduler.getScheduledThreadPoolExecutor().getQueue().size());
    }

    @Test
    void schedulingFailureReleasesOccupancy() {
        ThreadPoolTaskScheduler stopped = mock(ThreadPoolTaskScheduler.class);
        when(stopped.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenThrow(new IllegalStateException("stopped"));
        assertThrows(IllegalStateException.class, () -> new IdempotentGuard(redis, stopped).acquire(key, 1));
        assertNull(redis.opsForValue().get(RedisConstants.IDEMPOTENT + key));
    }

    @Test
    void renewFailureDoesNotAcknowledgeExecutionOrOverwriteAnotherOwner() {
        ThreadPoolTaskScheduler controlled = mock(ThreadPoolTaskScheduler.class);
        var callback = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        when(controlled.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(call -> {
                    callback.set(call.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        StringRedisTemplate template = spy(redis);
        try (IdempotentGuard.Lease lease = new IdempotentGuard(template, controlled).acquire(key, 1)) {
            doThrow(new IllegalStateException("Redis unavailable")).when(template)
                    .execute(
                            any(org.springframework.data.redis.core.script.RedisScript.class),
                            anyList(),
                            any(Object[].class));
            callback.get().run();
            assertThrows(IllegalStateException.class, () -> lease.complete(IdempotentGuard.DONE, 10));
        }
        assertTrue(redis.opsForValue().get(RedisConstants.IDEMPOTENT + key).startsWith("RUNNING:"));
    }

    @Test
    void invalidLeaseDoesNotCreateOccupancy() {
        assertThrows(IllegalArgumentException.class, () -> guard.acquire(key, 0));
        assertThrows(IllegalArgumentException.class, () -> guard.acquire(" ", 1));
        assertNull(redis.opsForValue().get(RedisConstants.IDEMPOTENT + key));
    }

    @Test
    void fiftyKeysUseOneAcquireAndOneRenewalTask() {
        List<String> keys = java.util.stream.IntStream.range(0, 50).mapToObj(i -> key + ":" + i).toList();
        keys.forEach(item -> extraKeys.add(RedisConstants.IDEMPOTENT + item));
        StringRedisTemplate template = spy(redis);
        try (IdempotentGuard.Lease lease = new IdempotentGuard(template, scheduler).acquireAll(keys, 30)) {
            assertTrue(lease.acquired());
            verify(template, times(1)).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                    anyList(), any(Object[].class));
            assertEquals(1, scheduler.getScheduledThreadPoolExecutor().getQueue().size());
            for (String item : extraKeys) assertEquals(lease.token(), redis.opsForValue().get(item));
            lease.complete(IdempotentGuard.DONE, 0);
        }
        assertTrue(extraKeys.stream().allMatch(item -> !Boolean.TRUE.equals(redis.hasKey(item))));
    }

    @Test
    void batchConflictDoesNotLeaveEarlierKeysClaimed() {
        String second = key + ":second";
        extraKeys.add(RedisConstants.IDEMPOTENT + second);
        try (IdempotentGuard.Lease existing = guard.acquire(second, 30)) {
            assertThrows(BizException.class, () -> guard.acquireAll(List.of(key, second), 30));
            assertNull(redis.opsForValue().get(RedisConstants.IDEMPOTENT + key));
            assertEquals(existing.token(), redis.opsForValue().get(RedisConstants.IDEMPOTENT + second));
        }
    }

    @Test
    void lostBatchKeyStopsRenewalButReleasesOnlyOwnedKeys() {
        String second = key + ":second";
        extraKeys.add(RedisConstants.IDEMPOTENT + second);
        try (IdempotentGuard.Lease lease = guard.acquireAll(List.of(key, second), 30)) {
            redis.opsForValue().set(RedisConstants.IDEMPOTENT + second, "RUNNING:other", Duration.ofSeconds(10));
            assertThrows(IllegalStateException.class, lease::assertOwned);
        }
        assertNull(redis.opsForValue().get(RedisConstants.IDEMPOTENT + key));
        assertEquals("RUNNING:other", redis.opsForValue().get(RedisConstants.IDEMPOTENT + second));
    }

    @Test
    void unknownAcquireOutcomeCleansOnlyItsOwnToken() {
        StringRedisTemplate template = spy(redis);
        doAnswer(call -> {
            List<?> result = (List<?>) call.callRealMethod();
            assertEquals("ACQUIRED", result.get(0));
            throw new IllegalStateException("response lost");
        }).when(template).execute(argThat(script -> List.class.equals(script.getResultType())), anyList(), any(Object[].class));
        assertThrows(IllegalStateException.class, () -> new IdempotentGuard(template, scheduler).acquire(key, 30));
        assertNull(redis.opsForValue().get(RedisConstants.IDEMPOTENT + key));
    }

    @Test
    void annotationExposesOwnershipDuringInvocationAndAlwaysClearsThreadContext() {
        var factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(new GuardedWork(guard));
        factory.addAspect(new IdempotentAspect(guard));
        GuardedWork work = factory.getProxy();
        assertThrows(IllegalStateException.class, guard::currentLease);
        work.run(key, false);
        assertThrows(IllegalStateException.class, guard::currentLease);
        assertThrows(IllegalStateException.class, () -> work.run(key, true));
        assertThrows(IllegalStateException.class, guard::currentLease);
        assertNull(redis.opsForValue().get(RedisConstants.IDEMPOTENT + key));
    }

    static class GuardedWork {
        final IdempotentGuard guard;

        GuardedWork(IdempotentGuard guard) {
            this.guard = guard;
        }

        @Idempotent(key = "#key", ttl = 0)
        public void run(String key, boolean fail) {
            guard.currentLease().assertOwned();
            if (fail) throw new IllegalStateException("business failed");
        }
    }
}

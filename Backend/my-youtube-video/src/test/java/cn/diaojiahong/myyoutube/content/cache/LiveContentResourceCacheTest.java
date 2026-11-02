package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.video.config.ContentResourceCacheProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两个独立客户端连接本机隔离 Redis，仅读写本次 UUID 对应的键，不清库。默认跳过，尚未接入 CI。
 * 在 Backend 目录运行（示例端口 16389 必须属于专门创建的隔离 Redis）：
 * <pre>{@code
 * mvn -pl my-youtube-video -am -Dtest=LiveContentResourceCacheTest \
 *   -Dcontent.cache.redis.port=16389 -Dcontent.cache.redis.isolated=true \
 *   -Dsurefire.failIfNoSpecifiedTests=false test
 * }</pre>
 * 可选参数为 content.cache.redis.password、content.cache.redis.database，数据库默认 0。
 */
@EnabledIfSystemProperty(named = "content.cache.redis.port", matches = "[0-9]+")
@Timeout(15)
class LiveContentResourceCacheTest {
    private static RedissonClient firstClient;
    private static RedissonClient secondClient;
    private static StringRedisTemplate firstRedis;
    private static StringRedisTemplate secondRedis;

    private final String resourceId = "cache-test:" + UUID.randomUUID();
    private final List<String> keys = new ArrayList<>();
    private final ContentResourceCacheProperties properties = new ContentResourceCacheProperties();
    private ContentResourceCache first;
    private ContentResourceCache second;

    @BeforeAll
    static void connect() {
        int port = Integer.getInteger("content.cache.redis.port");
        assertTrue(port > 10000 && port <= 65535, "仅使用高端口隔离测试 Redis");
        assertTrue(Boolean.getBoolean("content.cache.redis.isolated"), "必须显式确认使用隔离测试 Redis");
        firstClient = newClient();
        secondClient = newClient();
        firstRedis = new StringRedisTemplate(new RedissonConnectionFactory(firstClient));
        secondRedis = new StringRedisTemplate(new RedissonConnectionFactory(secondClient));
    }

    private static RedissonClient newClient() {
        var config = new Config();
        config.setThreads(2);
        config.setNettyThreads(2);
        config.setLockWatchdogTimeout(1500);
        SingleServerConfig server = config.useSingleServer();
        server.setAddress("redis://127.0.0.1:" + Integer.getInteger("content.cache.redis.port"));
        server.setPassword(System.getProperty("content.cache.redis.password"));
        server.setDatabase(Integer.getInteger("content.cache.redis.database", 0));
        server.setConnectTimeout(1000);
        server.setTimeout(1000);
        server.setRetryAttempts(0);
        server.setConnectionMinimumIdleSize(1);
        server.setConnectionPoolSize(4);
        server.setSubscriptionConnectionMinimumIdleSize(1);
        server.setSubscriptionConnectionPoolSize(2);
        return Redisson.create(config);
    }

    @BeforeEach
    void setup() {
        first = new ContentResourceCache(firstRedis, firstClient, properties);
        second = new ContentResourceCache(secondRedis, secondClient, properties);
        keys.add(ContentResourceRegion.PLAYLIST.key(resourceId));
    }

    @AfterEach
    void removeEntries() {
        firstRedis.delete(keys);
    }

    @AfterAll
    static void disconnect() {
        if (secondClient != null) secondClient.shutdown();
        if (firstClient != null) firstClient.shutdown();
    }

    @Test
    void concurrentRequestsAcrossClientsOnlyLoadOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(7);
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var readersStarted = new CountDownLatch(6);
        var loads = new AtomicInteger();
        try {
            Future<String> owner = pool.submit(() -> get(first, resourceId, () -> {
                loads.incrementAndGet();
                loading.countDown();
                await(release);
                return "#EXTM3U\n";
            }));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            List<Future<String>> readers = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                readers.add(pool.submit(() -> {
                    readersStarted.countDown();
                    return get(second, resourceId, () -> {
                        loads.incrementAndGet();
                        return "duplicate-load";
                    });
                }));
            }
            assertTrue(readersStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> readers.get(0).get(100, TimeUnit.MILLISECONDS));
            assertEquals(1, loads.get());

            release.countDown();
            assertEquals("#EXTM3U\n", owner.get(5, TimeUnit.SECONDS));
            for (Future<String> reader : readers) assertEquals("#EXTM3U\n", reader.get(5, TimeUnit.SECONDS));
            assertEquals(1, loads.get());
            assertFalse(firstClient.getLock("lock:" + keys.get(0)).isLocked());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void missingResourceIsSharedAsANegativeEntryWithAShortTtl() {
        var loads = new AtomicInteger();
        assertNull(get(first, resourceId, () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertNull(get(second, resourceId, () -> {
            loads.incrementAndGet();
            return "should-not-load";
        }));

        assertEquals(1, loads.get());
        assertEquals("null", firstRedis.opsForValue().get(keys.get(0)));
        Long ttl = firstRedis.getExpire(keys.get(0), TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 3000);
    }

    @Test
    void failedLoaderLeavesNoNegativeEntryAndAnotherClientCanRetry() {
        assertThrows(IllegalStateException.class, () -> get(first, resourceId, () -> {
            throw new IllegalStateException("object storage unavailable");
        }));
        assertNull(firstRedis.opsForValue().get(keys.get(0)));
        assertFalse(firstClient.getLock("lock:" + keys.get(0)).isLocked());

        assertEquals("recovered", get(second, resourceId, () -> "recovered"));
    }

    @Test
    void evictionMakesTheNextClientReloadTheResource() {
        assertEquals("old-resource", get(first, resourceId, () -> "old-resource"));
        assertTrue(second.evict(ContentResourceRegion.PLAYLIST, resourceId));
        assertEquals("new-resource", get(first, resourceId, () -> "new-resource"));
    }

    @Test
    void watchdogKeepsTheLoaderExclusivePastTheInitialLease() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<String> owner = pool.submit(() -> get(first, resourceId, () -> {
                loading.countDown();
                await(release);
                return "slow-storage";
            }));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            // 超过客户端初始的 1.5 秒租期，第二个客户端仍不能开始回源。
            assertFalse(release.await(2300, TimeUnit.MILLISECONDS));
            properties.setLockWaitMillis(0);
            assertThrows(DependencyException.class, () -> get(second, resourceId,
                    () -> fail("watchdog must keep the first loader exclusive")));
            assertTrue(firstClient.getLock("lock:" + keys.get(0)).remainTimeToLive() > 0);

            release.countDown();
            assertEquals("slow-storage", owner.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void differentResourcesCanLoadWhileAnotherResourceIsBlocked() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String otherId = resourceId + ":other";
        keys.add(ContentResourceRegion.PLAYLIST.key(otherId));
        try {
            Future<String> owner = pool.submit(() -> get(first, resourceId, () -> {
                loading.countDown();
                await(release);
                return "first-resource";
            }));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            properties.setLockWaitMillis(0);

            assertEquals("second-resource", get(second, otherId, () -> "second-resource"));

            release.countDown();
            assertEquals("first-resource", owner.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static String get(ContentResourceCache cache, String id, Supplier<String> loader) {
        return cache.get(ContentResourceRegion.PLAYLIST, id, String.class, loader);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(8, TimeUnit.SECONDS), "loader was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}

package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader.FillPolicy;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackMetadata;
import cn.diaojiahong.myyoutube.common.media.VideoPlayback;
import cn.diaojiahong.myyoutube.common.media.ContentArtifacts;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.ContentUnderstandingResource;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.*;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.content.strategy.VideoContentStrategy;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import software.amazon.awssdk.http.SdkHttpMethod;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
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
        first = new ContentResourceCache(firstRedis, firstClient, properties, metrics);
        second = new ContentResourceCache(secondRedis, secondClient, properties, metrics);
        keys.add(ContentResourceRegion.PLAYLIST.key(resourceId));
    }

    @AfterEach
    void removeEntries() {
        firstRedis.delete(keys);
        metrics.close();
    }

    @AfterAll
    static void disconnect() {
        if (secondClient != null) secondClient.shutdown();
        if (firstClient != null) firstClient.shutdown();
    }

    @ParameterizedTest
    @EnumSource(FillPolicy.class)
    void concurrentRequestsAcrossClientsOnlyLoadOnce(FillPolicy policy) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(7);
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var readersStarted = new CountDownLatch(6);
        var loads = new AtomicInteger();
        try {
            Future<String> owner = pool.submit(() -> first.get(ContentResourceRegion.PLAYLIST, resourceId, String.class, () -> {
                loads.incrementAndGet();
                loading.countDown();
                await(release);
                return "#EXTM3U\n";
            }, policy));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            List<Future<String>> readers = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                readers.add(pool.submit(() -> {
                    readersStarted.countDown();
                    ContentResourceReader reader = second.readTogether(resourceId, ContentResourceRegion.PLAYLIST);
                    return reader.get(ContentResourceRegion.PLAYLIST, resourceId, String.class, () -> {
                        loads.incrementAndGet();
                        return "duplicate-load";
                    }, policy);
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
    void warmupSkipsAnActiveViewerLoadAndReleasesOtherAcquiredLocks() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var warmupLoads = new AtomicInteger();
        String unusedKey = ContentResourceRegion.UNDERSTANDING.key(resourceId);
        keys.add(unusedKey);
        firstRedis.opsForValue().set(unusedKey, JsonUtil.toJson("old-value"), Duration.ofSeconds(60));
        try {
            Future<String> owner = pool.submit(() -> get(first, resourceId, () -> {
                loading.countDown();
                await(release);
                return "viewer-loaded";
            }));
            assertTrue(loading.await(5, TimeUnit.SECONDS));

            ContentResourceCache.WarmupResult result = second.warmupTogether(Runnable::run,
                    new WarmupResource<>(ContentResourceRegion.PLAYLIST, resourceId, () -> {
                        warmupLoads.incrementAndGet();
                        return "warmup-loaded";
                    }, FillPolicy.STANDARD),
                    new WarmupResource<>(ContentResourceRegion.UNDERSTANDING, resourceId,
                            () -> "independent", FillPolicy.STANDARD));
            assertNotNull(result.failure());
            assertInstanceOf(ContentResourceBusyException.class, result.failure().getCause());

            assertEquals(0, warmupLoads.get());
            assertFalse(owner.isDone());
            assertNull(secondRedis.opsForValue().get(keys.get(0)));
            assertFalse(firstClient.getLock("lock:" + unusedKey).isLocked());
            assertEquals(JsonUtil.toJson("independent"), firstRedis.opsForValue().get(unusedKey));
            assertTrue(firstRedis.getExpire(unusedKey, TimeUnit.SECONDS) > 1400);
            release.countDown();
            assertEquals("viewer-loaded", owner.get(5, TimeUnit.SECONDS));
            assertEquals("viewer-loaded", get(second, resourceId, () -> fail("should use viewer cache")));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void asyncWarmupKeepsTheWatchdogAndReleasesWithItsOriginalOwner() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<ContentResourceCache.WarmupResult> owner = pool.submit(() -> first.warmupTogether(Runnable::run,
                    new WarmupResource<>(ContentResourceRegion.PLAYLIST, resourceId, () -> {
                        loading.countDown();
                        await(release);
                        return "async-warmed";
                    }, FillPolicy.STANDARD)));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> owner.get(2300, TimeUnit.MILLISECONDS));
            properties.setLockWaitMillis(0);
            assertThrows(ContentResourceBusyException.class, () -> get(second, resourceId,
                    () -> fail("async warmup watchdog must keep the lock")));

            release.countDown();
            assertNull(owner.get(5, TimeUnit.SECONDS).failure());
            assertFalse(secondClient.getLock("lock:" + keys.get(0)).isLocked());
            assertEquals("async-warmed", get(second, resourceId, () -> fail("warmup should have filled the cache")));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void parallelWarmupKeepsBothLocksUntilBothLoadersFinishAndSharesTheResults() throws Exception {
        String mediaKey = ContentResourceRegion.CONTENT_MEDIA.key(resourceId);
        String understandingKey = ContentResourceRegion.UNDERSTANDING.key(resourceId);
        keys.add(mediaKey);
        keys.add(understandingKey);
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        ExecutorService parent = Executors.newSingleThreadExecutor();
        ExecutorService loaders = Executors.newFixedThreadPool(2);
        try {
            Future<ContentResourceCache.WarmupResult> task = parent.submit(() -> first.warmupTogether(loaders,
                    new WarmupResource<>(ContentResourceRegion.CONTENT_MEDIA, resourceId, () -> {
                        started.countDown();
                        await(release);
                        return "media";
                    }, FillPolicy.STANDARD),
                    new WarmupResource<>(ContentResourceRegion.UNDERSTANDING, resourceId, () -> {
                        started.countDown();
                        await(release);
                        return "understanding";
                    }, FillPolicy.STANDARD)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            // 等待跨过初始租期，父线程等待子查询期间两把锁仍应由 watchdog 续期。
            assertThrows(TimeoutException.class, () -> task.get(2300, TimeUnit.MILLISECONDS));
            properties.setLockWaitMillis(0);
            for (ContentResourceRegion region : List.of(ContentResourceRegion.CONTENT_MEDIA, ContentResourceRegion.UNDERSTANDING)) {
                assertNull(secondRedis.opsForValue().get(region.key(resourceId)));
                assertThrows(ContentResourceBusyException.class, () -> second.get(region, resourceId, String.class,
                        () -> fail("parallel warmup must keep each loader exclusive"), FillPolicy.STANDARD));
            }

            release.countDown();
            assertNull(task.get(5, TimeUnit.SECONDS).failure());
            assertEquals("media", second.get(ContentResourceRegion.CONTENT_MEDIA, resourceId, String.class,
                    () -> fail("media should be cached"), FillPolicy.STANDARD));
            assertEquals("understanding", second.get(ContentResourceRegion.UNDERSTANDING, resourceId, String.class,
                    () -> fail("understanding should be cached"), FillPolicy.STANDARD));
            assertFalse(secondClient.getLock("lock:" + mediaKey).isLocked());
            assertFalse(secondClient.getLock("lock:" + understandingKey).isLocked());
        } finally {
            release.countDown();
            parent.shutdownNow();
            loaders.shutdownNow();
        }
    }

    @Test
    void warmupFlushesDistinctPlaylistsBeforeUnlockingAndNormalReadersReuseThem() {
        String secondId = resourceId + ":1080p";
        String firstKey = ContentResourceRegion.PLAYLIST.key(resourceId);
        String secondKey = ContentResourceRegion.PLAYLIST.key(secondId);
        keys.add(secondKey);

        ContentResourceCache.WarmupResult result = first.warmupTogether(Runnable::run,
                new WarmupResource<>(ContentResourceRegion.PLAYLIST, resourceId, () -> "720p", FillPolicy.STANDARD),
                new WarmupResource<>(ContentResourceRegion.PLAYLIST, secondId, () -> {
                    assertNull(secondRedis.opsForValue().get(firstKey));
                    assertNull(secondRedis.opsForValue().get(secondKey));
                    assertTrue(secondClient.getLock("lock:" + firstKey).isLocked());
                    assertTrue(secondClient.getLock("lock:" + secondKey).isLocked());
                    return "1080p";
                }, FillPolicy.STANDARD));

        assertNull(result.failure());
        assertEquals("720p", get(second, resourceId, () -> fail("first playlist should be cached")));
        assertEquals("1080p", get(second, secondId, () -> fail("second playlist should be cached")));
        for (String key : List.of(firstKey, secondKey)) {
            Long ttl = secondRedis.getExpire(key, TimeUnit.MILLISECONDS);
            assertNotNull(ttl);
            assertTrue(ttl >= 1439000 && ttl <= 1800000, "ttl=" + ttl);
            assertFalse(secondClient.getLock("lock:" + key).isLocked());
        }
    }

    @Test
    void shortAndStandardTtlsShareResultsAcrossClientsWithoutChangingNegativeTtl() {
        var loads = new AtomicInteger();
        Supplier<String> source = () -> "value-" + loads.incrementAndGet();

        assertEquals("value-1", first.get(ContentResourceRegion.PLAYLIST, resourceId, String.class,
                source, FillPolicy.SHORT_TTL));
        assertEquals("value-1", second.get(ContentResourceRegion.PLAYLIST, resourceId, String.class,
                source, FillPolicy.SHORT_TTL));
        Long shortTtl = secondRedis.getExpire(keys.get(0), TimeUnit.MILLISECONDS);
        assertNotNull(shortTtl);
        assertTrue(shortTtl > 0 && shortTtl <= 60000);
        assertEquals(1, loads.get());

        first.evict(ContentResourceRegion.PLAYLIST, resourceId);
        assertEquals("value-2", first.get(ContentResourceRegion.PLAYLIST, resourceId, String.class,
                source, FillPolicy.STANDARD));
        assertEquals("value-2", second.get(ContentResourceRegion.PLAYLIST, resourceId, String.class,
                source, FillPolicy.SHORT_TTL));
        Long standardTtl = secondRedis.getExpire(keys.get(0), TimeUnit.MILLISECONDS);
        assertNotNull(standardTtl);
        assertTrue(standardTtl > 60000 && standardTtl <= 1800000);
        assertEquals(2, loads.get());

        first.evict(ContentResourceRegion.PLAYLIST, resourceId);
        assertNull(first.get(ContentResourceRegion.PLAYLIST, resourceId, String.class,
                () -> null, FillPolicy.SHORT_TTL));
        assertNull(second.get(ContentResourceRegion.PLAYLIST, resourceId, String.class,
                () -> fail("negative entry must be shared"), FillPolicy.SHORT_TTL));
        Long ttl = secondRedis.getExpire(keys.get(0), TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 3000);
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

    @ParameterizedTest
    @EnumSource(FillPolicy.class)
    void malformedEntryIsRebuiltAndSharedWithTheSelectedTtl(FillPolicy policy) {
        String key = keys.get(0);
        firstRedis.opsForValue().set(key, "{broken", Duration.ofMinutes(30));

        assertEquals("rebuilt", first.get(ContentResourceRegion.PLAYLIST, resourceId, String.class, () -> "rebuilt", policy));
        assertEquals("rebuilt", second.get(ContentResourceRegion.PLAYLIST, resourceId, String.class,
                () -> fail("another client must use the repaired entry"), policy));
        assertEquals(JsonUtil.toJson("rebuilt"), secondRedis.opsForValue().get(key));
        Long ttl = secondRedis.getExpire(key, TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        long maximum = policy == FillPolicy.SHORT_TTL ? 60000 : 1800000;
        assertTrue(ttl > 0 && ttl <= maximum);
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
        second.evict(ContentResourceRegion.PLAYLIST, resourceId);
        assertEquals("new-resource", get(first, resourceId, () -> "new-resource"));
    }

    @Test
    void completedUnderstandingReplacesNegativeCacheAndIsSharedAcrossClients() {
        long id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String key = ContentResourceRegion.UNDERSTANDING.key(Long.toString(id));
        keys.add(key);
        ContentUnderstandingMapper rows = mock(ContentUnderstandingMapper.class);
        ContentArtifacts.Chapter chapter = new ContentArtifacts.Chapter(1000, "第一章");
        ContentUnderstanding row = new ContentUnderstanding().setContentId(id).setReceived(false)
                .setSummary("摘要").setChaptersJson(JsonUtil.toJson(List.of(chapter)))
                .setSubtitleKey(resourceId + "/subtitle.vtt");
        when(rows.selectById(id)).thenReturn(row);
        var loader = new ContentUnderstandingResourceLoader(rows);
        var firstResources = new ContentUnderstandingResources(loader);
        var secondResources = new ContentUnderstandingResources(loader);
        assertNull(firstResources.get(id, first, FillPolicy.STANDARD));
        assertNull(secondResources.get(id, second, FillPolicy.STANDARD));
        verify(rows).selectById(id);

        row.setReceived(true);
        var invalidator = new ContentResourceInvalidator(first, mock(TaskScheduler.class), properties, metrics);
        invalidator.evictAfterCommit(ContentResourceRegion.UNDERSTANDING, Long.toString(id));
        ContentUnderstandingResource loaded = secondResources.get(id, second, FillPolicy.STANDARD);
        ContentUnderstandingResource cached = firstResources.get(id, first, FillPolicy.STANDARD);

        assertEquals(loaded, cached);
        assertEquals(List.of(chapter), cached.summary().chapters());
        assertEquals(row.getSubtitleKey(), cached.subtitleKey());
        verify(rows, times(2)).selectById(id);
        Long ttl = firstRedis.getExpire(key, TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= TimeUnit.SECONDS.toMillis(properties.getArtifactTtlSeconds()));
    }

    @Test
    void secondDeletionRemovesAStaleLoaderRefillAcrossClients() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        var invalidator = new ContentResourceInvalidator(second, scheduler, properties, metrics);
        try {
            Future<String> oldRead = pool.submit(() -> get(first, resourceId, () -> {
                loading.countDown();
                await(release);
                return "old-resource";
            }));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            invalidator.evictAfterCommit(ContentResourceRegion.PLAYLIST, resourceId);
            assertNull(secondRedis.opsForValue().get(keys.get(0)));

            release.countDown();
            assertEquals("old-resource", oldRead.get(5, TimeUnit.SECONDS));
            assertNotNull(secondRedis.opsForValue().get(keys.get(0)));

            ArgumentCaptor<Runnable> delayed = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).schedule(delayed.capture(), any(Instant.class));
            delayed.getValue().run();
            assertNull(firstRedis.opsForValue().get(keys.get(0)));
            assertEquals("new-resource", get(second, resourceId, () -> "new-resource"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void refillAfterBothDeletionsReliesOnTheStateTtl() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var loading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        var invalidator = new ContentResourceInvalidator(second, scheduler, properties, metrics);
        ContentResourceRegion region = ContentResourceRegion.CONTENT_STATE;
        String key = region.key(resourceId);
        keys.add(key);
        try {
            Future<String> oldRead = pool.submit(() -> first.get(region, resourceId, String.class, () -> {
                loading.countDown();
                await(release);
                return "old-state";
            }, FillPolicy.STANDARD));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            invalidator.evictAfterCommit(region, resourceId);
            ArgumentCaptor<Runnable> delayed = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).schedule(delayed.capture(), any(Instant.class));
            delayed.getValue().run();
            assertNull(secondRedis.opsForValue().get(key));

            // 两次删除都已完成，再放行旧 loader；双删不阻止这次迟到回填。
            release.countDown();
            assertEquals("old-state", oldRead.get(5, TimeUnit.SECONDS));
            assertEquals("old-state", second.get(region, resourceId, String.class,
                    () -> fail("late refill remains cached until its TTL expires"), FillPolicy.STANDARD));
            Long ttl = secondRedis.getExpire(key, TimeUnit.MILLISECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= TimeUnit.SECONDS.toMillis(properties.getStateTtlSeconds()));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void batchPreservesHitsNegativesAndRechecksMissesAcrossClients() {
        keys.add(ContentResourceRegion.CONTENT_STATE.key(resourceId));
        keys.add(ContentResourceRegion.CONTENT_MEDIA.key(resourceId));
        keys.add(ContentResourceRegion.UNDERSTANDING.key(resourceId));
        first.get(ContentResourceRegion.CONTENT_STATE, resourceId, String.class, () -> "state", FillPolicy.STANDARD);
        first.get(ContentResourceRegion.UNDERSTANDING, resourceId, String.class, () -> null, FillPolicy.STANDARD);

        ContentResourceReader reader = second.readTogether(resourceId, ContentResourceRegion.CONTENT_STATE,
                ContentResourceRegion.CONTENT_MEDIA, ContentResourceRegion.UNDERSTANDING);
        first.get(ContentResourceRegion.CONTENT_MEDIA, resourceId, String.class, () -> "media", FillPolicy.STANDARD);

        assertEquals("state", reader.get(ContentResourceRegion.CONTENT_STATE, resourceId, String.class,
                () -> fail("state must be read from MGET"), FillPolicy.STANDARD));
        assertNull(reader.get(ContentResourceRegion.UNDERSTANDING, resourceId, String.class,
                () -> fail("negative entry must be read from MGET"), FillPolicy.STANDARD));
        assertEquals("media", reader.get(ContentResourceRegion.CONTENT_MEDIA, resourceId, String.class,
                () -> fail("miss must be rechecked after locking"), FillPolicy.STANDARD));
    }

    @Test
    void realRedisSharesRawPlaybackResourcesButNotSignaturesOrAccessDecisions() {
        long contentId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String playlistKey = resourceId + "/index.m3u8";
        String mediaKey = resourceId + "/media.mp4";
        keys.add(ContentResourceRegion.CONTENT_MEDIA.key(Long.toString(contentId)));
        keys.add(ContentResourceRegion.CONTENT_STATE.key(Long.toString(contentId)));
        keys.add(ContentResourceRegion.PLAYLIST.key(playlistKey));
        ContentMapper contents = mock(ContentMapper.class);
        VideoMapper videos = mock(VideoMapper.class);
        S3StorageUtil storage = mock(S3StorageUtil.class);
        Content content = new Content().setId(contentId).setAuthorId(9L).setType(ContentTypeEnum.VIDEO)
                .setIsDelete(0).setStatus(ContentStatusEnum.PUBLISHED).setVisibility("public").setPlayCount(100L);
        when(contents.selectById(contentId)).thenReturn(content);
        VideoPlayback playback = new VideoPlayback(1280, 720, List.of(new VideoPlayback.Rendition(
                720, mediaKey, playlistKey, 1280, 720, 3000000, new PlaybackMetadata("avc1.64001f", 3000000, 25))));
        when(videos.selectById(contentId)).thenReturn(new Video().setId(contentId).setObjectKey("original.mp4")
                .setDurationMs(12000L).setPlaybackJson(JsonUtil.toJson(playback)));
        String raw = "#EXTM3U\n#EXTINF:4.0,\nmedia.mp4\n#EXT-X-ENDLIST\n";
        when(storage.readObject(playlistKey, Duration.ofSeconds(10))).thenReturn(raw.getBytes(StandardCharsets.UTF_8));
        when(storage.presignReadUrl(mediaKey, 1200, SdkHttpMethod.GET))
                .thenReturn("https://signed.example/media.mp4?signature=one", "https://signed.example/media.mp4?signature=two");
        var strategies = new ContentTypeStrategyRegistry(List.of(new VideoContentStrategy(videos)));
        var loader = new ContentMediaResourceLoader(strategies);
        AudioContentAccess audioAccess = mock(AudioContentAccess.class);
        var access = new ContentAccessService(contents, audioAccess, org.mockito.Mockito.mock(UserBlockPort.class));
        var stateLoader = new ContentAccessSnapshotLoader(contents, audioAccess);
        var firstAccess = new ContentDisplayAccess(first, stateLoader, access);
        var secondAccess = new ContentDisplayAccess(second, stateLoader, access);
        var tokens = new PlaybackTokens("live-resource-cache-test-secret-at-least-thirty-two", 21600);
        var firstStreams = new ContentStreamService(first, firstAccess, new ContentMediaResources(loader, storage), storage, tokens, properties);
        var secondStreams = new ContentStreamService(second, secondAccess, new ContentMediaResources(loader, storage), storage, tokens, properties);
        String ticket = tokens.issue(9, contentId, 12000, PlaybackTokens.Scope.VIEW).token();

        assertTrue(firstStreams.playlist(contentId, 720, 9L, ticket).contains("signature=one"));
        assertTrue(secondStreams.playlist(contentId, 720, 9L, ticket).contains("signature=two"));
        verify(contents).selectById(contentId);
        content.setStatus(ContentStatusEnum.BANNED);
        first.evict(ContentResourceRegion.CONTENT_STATE, Long.toString(contentId));
        assertThrows(BizException.class, () -> secondStreams.playlist(contentId, 720, 9L, ticket));

        verify(videos).selectById(contentId);
        verify(contents, times(2)).selectById(contentId);
        verify(storage).readObject(playlistKey, Duration.ofSeconds(10));
        verify(storage, times(2)).presignReadUrl(mediaKey, 1200, SdkHttpMethod.GET);
        assertEquals(raw, JsonUtil.fromJson(firstRedis.opsForValue().get(ContentResourceRegion.PLAYLIST.key(playlistKey)), String.class));
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
        return cache.get(ContentResourceRegion.PLAYLIST, id, String.class, loader, FillPolicy.STANDARD);
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

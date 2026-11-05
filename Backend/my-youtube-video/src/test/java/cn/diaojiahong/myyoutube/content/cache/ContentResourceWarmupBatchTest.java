package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader.FillPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RLock;
import org.redisson.misc.CompletableFutureWrapper;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class ContentResourceWarmupBatchTest {
    private final String id = "1";
    private final String mediaKey = ContentResourceRegion.CONTENT_MEDIA.key(id);
    private final String understandingKey = ContentResourceRegion.UNDERSTANDING.key(id);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisConnection connection = mock(RedisConnection.class);
    private final RedisStringCommands commands = mock(RedisStringCommands.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock mediaLock = mock(RLock.class);
    private final RLock understandingLock = mock(RLock.class);
    private final AtomicBoolean mediaHeld = new AtomicBoolean();
    private final AtomicBoolean understandingHeld = new AtomicBoolean();
    @SuppressWarnings("unchecked")
    private final Supplier<String> mediaLoader = mock(Supplier.class);
    @SuppressWarnings("unchecked")
    private final Supplier<String> understandingLoader = mock(Supplier.class);
    private final Map<String, String> written = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ContentResourceCache cache = new ContentResourceCache(redis, redisson,
            new ContentResourceCacheProperties(), metrics);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.getLock("lock:" + mediaKey)).thenReturn(mediaLock);
        when(redisson.getLock("lock:" + understandingKey)).thenReturn(understandingLock);
        when(mediaLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenAnswer(call -> {
            mediaHeld.set(true);
            return new CompletableFutureWrapper<>(true);
        });
        when(understandingLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenAnswer(call -> {
            understandingHeld.set(true);
            return new CompletableFutureWrapper<>(true);
        });
        when(mediaLock.unlockAsync(anyLong())).thenAnswer(call -> {
            mediaHeld.set(false);
            return new CompletableFutureWrapper<Void>(CompletableFuture.completedFuture(null));
        });
        when(understandingLock.unlockAsync(anyLong())).thenAnswer(call -> {
            understandingHeld.set(false);
            return new CompletableFutureWrapper<Void>(CompletableFuture.completedFuture(null));
        });
        when(mediaLoader.get()).thenReturn("media");
        when(understandingLoader.get()).thenReturn("understanding");
        when(connection.stringCommands()).thenReturn(commands);
        when(commands.set(any(byte[].class), any(byte[].class), any(Expiration.class), any())).thenAnswer(call -> {
            String key = StringRedisSerializer.UTF_8.deserialize(call.getArgument(0));
            assertTrue(key.equals(mediaKey) ? mediaHeld.get() : understandingHeld.get(), "写入时仍须持有对应加载锁");
            written.put(key, StringRedisSerializer.UTF_8.deserialize(call.getArgument(1)));
            Expiration expiration = call.getArgument(2);
            ttls.put(key, Duration.ofMillis(expiration.getExpirationTimeInMilliseconds()));
            return true;
        });
        when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(call -> {
            RedisCallback<?> pipeline = call.getArgument(0);
            pipeline.doInRedis(connection);
            return List.of();
        });
    }

    @AfterEach
    void close() {
        metrics.close();
    }

    @Test
    void warmupUsesIndependentLocksAndPayloadsForTwoKeysInTheSameRegion() {
        WarmupResource<String> first = new WarmupResource<>(
                ContentResourceRegion.PLAYLIST, "720.m3u8", () -> "720p", FillPolicy.STANDARD);
        WarmupResource<String> second = new WarmupResource<>(
                ContentResourceRegion.PLAYLIST, "1080.m3u8", () -> "1080p", FillPolicy.STANDARD);
        when(redisson.getLock("lock:" + first.key())).thenReturn(mediaLock);
        when(redisson.getLock("lock:" + second.key())).thenReturn(understandingLock);

        ContentResourceCache.WarmupResult result = cache.warmupTogether(Runnable::run, first, second);

        assertNull(result.failure());
        assertEquals("720p", result.value(first, String.class));
        assertEquals("1080p", result.value(second, String.class));
        assertEquals(Map.of(first.key(), JsonUtil.toJson("720p"), second.key(), JsonUtil.toJson("1080p")), written);
        verify(redis).executePipelined(any(RedisCallback.class));
        assertReleased();
    }

    @Test
    void refreshesWithoutCacheReadsAndHoldsBothLocksUntilFlush() {
        when(mediaLoader.get()).thenAnswer(call -> {
            assertTrue(mediaHeld.get());
            assertTrue(understandingHeld.get());
            verify(redis, never()).executePipelined(any(RedisCallback.class));
            return "media";
        });
        warm();

        assertEquals(Map.of(mediaKey, JsonUtil.toJson("media"), understandingKey, JsonUtil.toJson("understanding")), written);
        verifyNoInteractions(values);
        verify(redis).executePipelined(any(RedisCallback.class));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        assertTtl(mediaKey, 1440000, 1800000);
        assertTtl(understandingKey, 1440000, 1800000);
        assertReleased();
        assertEquals(1, count(MetricNames.CONTENT_CACHE_FILLS, ContentResourceRegion.CONTENT_MEDIA));
        assertEquals(1, count(MetricNames.CONTENT_CACHE_FILLS, ContentResourceRegion.UNDERSTANDING));
    }

    @Test
    void issuesBothAcquisitionsBeforeWaitingAndKeepsOneOwnerId() throws Exception {
        var firstReply = new CompletableFuture<Boolean>();
        var secondRequested = new CountDownLatch(1);
        var mediaOwner = new AtomicLong();
        var understandingOwner = new AtomicLong();
        when(mediaLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenAnswer(call -> {
            mediaOwner.set(call.getArgument(3));
            return new CompletableFutureWrapper<>(firstReply.thenApply(acquired -> {
                mediaHeld.set(acquired);
                return acquired;
            }));
        });
        when(understandingLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenAnswer(call -> {
            understandingOwner.set(call.getArgument(3));
            understandingHeld.set(true);
            secondRequested.countDown();
            return new CompletableFutureWrapper<>(true);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = pool.submit(this::warm);
            assertTrue(secondRequested.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            verifyNoInteractions(mediaLoader, understandingLoader);

            firstReply.complete(true);
            task.get(5, TimeUnit.SECONDS);

            assertEquals(mediaOwner.get(), understandingOwner.get());
            verify(mediaLock).unlockAsync(mediaOwner.get());
            verify(understandingLock).unlockAsync(mediaOwner.get());
            verify(mediaLock, never()).tryLock(anyLong(), any());
            verify(understandingLock, never()).tryLock(anyLong(), any());
            assertReleased();
        } finally {
            firstReply.complete(true);
            pool.shutdownNow();
        }
    }

    @Test
    void issuesBothUnlocksBeforeWaitingForTheirResponses() throws Exception {
        var firstReply = new CompletableFuture<Void>();
        var secondRequested = new CountDownLatch(1);
        var owner = new AtomicLong();
        when(mediaLock.unlockAsync(anyLong())).thenAnswer(call -> {
            owner.set(call.getArgument(0));
            return new CompletableFutureWrapper<>(firstReply.thenRun(() -> mediaHeld.set(false)));
        });
        when(understandingLock.unlockAsync(anyLong())).thenAnswer(call -> {
            understandingHeld.set(false);
            secondRequested.countDown();
            return new CompletableFutureWrapper<Void>(CompletableFuture.completedFuture(null));
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = pool.submit(this::warm);
            assertTrue(secondRequested.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            assertEquals(2, written.size());

            firstReply.complete(null);
            task.get(5, TimeUnit.SECONDS);

            verify(mediaLock).tryLockAsync(0, -1, TimeUnit.MILLISECONDS, owner.get());
            verify(understandingLock).tryLockAsync(0, -1, TimeUnit.MILLISECONDS, owner.get());
            verify(understandingLock).unlockAsync(owner.get());
            verify(mediaLock, never()).unlock();
            verify(understandingLock, never()).unlock();
            assertReleased();
        } finally {
            firstReply.complete(null);
            pool.shutdownNow();
        }
    }

    @Test
    void runsBothLoadersInParallelAndCollectsTheirResultsOnTheOwnerThread() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var owner = new AtomicReference<Thread>();
        when(mediaLoader.get()).thenAnswer(call -> {
            assertNotSame(owner.get(), Thread.currentThread());
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "media";
        });
        when(understandingLoader.get()).thenAnswer(call -> {
            assertNotSame(owner.get(), Thread.currentThread());
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "understanding";
        });
        ExecutorService parent = Executors.newSingleThreadExecutor();
        ExecutorService loaders = Executors.newFixedThreadPool(2);
        try {
            Future<?> task = parent.submit(() -> {
                owner.set(Thread.currentThread());
                prepare(loaders);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS), "两项回源必须同时开始，不能等待第一项完成才提交第二项");
            assertTrue(mediaHeld.get());
            assertTrue(understandingHeld.get());
            verify(redis, never()).executePipelined(any(RedisCallback.class));
            release.countDown();
            task.get(5, TimeUnit.SECONDS);

            assertEquals(Map.of(mediaKey, JsonUtil.toJson("media"), understandingKey, JsonUtil.toJson("understanding")), written);
            verify(mediaLoader).get();
            verify(understandingLoader).get();
            verify(mediaLock).unlockAsync(owner.get().getId());
            verify(understandingLock).unlockAsync(owner.get().getId());
            assertReleased();
        } finally {
            release.countDown();
            parent.shutdownNow();
            loaders.shutdownNow();
        }
    }

    @Test
    void waitsForTheOtherLoaderAfterOneFailsAndStillCachesItsSuccessfulResult() throws Exception {
        var failure = new IllegalStateException("media unavailable");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(mediaLoader.get()).thenThrow(failure);
        when(understandingLoader.get()).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "understanding";
        });
        ExecutorService parent = Executors.newSingleThreadExecutor();
        ExecutorService loaders = Executors.newFixedThreadPool(2);
        try {
            Future<?> task = parent.submit(() -> prepare(loaders));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            verify(mediaLock, never()).unlockAsync(anyLong());
            verify(understandingLock, never()).unlockAsync(anyLong());
            release.countDown();

            ExecutionException observed = assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ContentResourceWarmupException.class, observed.getCause());
            assertSame(failure, observed.getCause().getCause());
            assertEquals(Map.of(understandingKey, JsonUtil.toJson("understanding")), written);
            assertReleased();
        } finally {
            release.countDown();
            parent.shutdownNow();
            loaders.shutdownNow();
        }
    }

    @Test
    void rejectionOfOneLoaderStillWaitsForAcceptedWorkBeforeUnlocking() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(mediaLoader.get()).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "media";
        });
        ExecutorService parent = Executors.newSingleThreadExecutor();
        ExecutorService loader = Executors.newSingleThreadExecutor();
        var submissions = new AtomicInteger();
        Executor rejectSecond = task -> {
            if (submissions.incrementAndGet() == 2) {
                throw new RejectedExecutionException("loader queue full");
            }
            loader.execute(task);
        };
        try {
            Future<?> task = parent.submit(() -> prepare(rejectSecond));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone());
            verify(mediaLock, never()).unlockAsync(anyLong());
            release.countDown();

            ExecutionException observed = assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ContentResourceWarmupException.class, observed.getCause());
            assertInstanceOf(RejectedExecutionException.class, observed.getCause().getCause());
            assertEquals(Map.of(mediaKey, JsonUtil.toJson("media")), written);
            verifyNoInteractions(understandingLoader);
            assertReleased();
        } finally {
            release.countDown();
            parent.shutdownNow();
            loader.shutdownNow();
        }
    }

    @Test
    void interruptionDuringLoadsWaitsForBothQueriesAndThenReleasesLocks() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var owner = new AtomicReference<Thread>();
        when(mediaLoader.get()).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "media";
        });
        when(understandingLoader.get()).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "understanding";
        });
        ExecutorService parent = Executors.newSingleThreadExecutor();
        ExecutorService loaders = Executors.newFixedThreadPool(2);
        try {
            Future<?> task = parent.submit(() -> {
                owner.set(Thread.currentThread());
                assertThrows(DependencyException.class, () -> prepare(loaders));
                assertTrue(Thread.currentThread().isInterrupted());
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            owner.get().interrupt();
            verify(mediaLock, never()).unlockAsync(anyLong());
            verify(understandingLock, never()).unlockAsync(anyLong());
            release.countDown();
            task.get(5, TimeUnit.SECONDS);

            assertTrue(written.isEmpty());
            assertReleased();
        } finally {
            release.countDown();
            parent.shutdownNow();
            loaders.shutdownNow();
        }
    }

    @Test
    void interruptionStillReleasesLocksWhoseAcquisitionResponseArrivesLater() throws Exception {
        var firstReply = new CompletableFuture<Boolean>();
        var secondRequested = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        when(mediaLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenReturn(
                new CompletableFutureWrapper<>(firstReply.thenApply(acquired -> {
                    mediaHeld.set(acquired);
                    return acquired;
                })));
        when(understandingLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenAnswer(call -> {
            understandingHeld.set(true);
            secondRequested.countDown();
            return new CompletableFutureWrapper<>(true);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = pool.submit(() -> {
                worker.set(Thread.currentThread());
                assertThrows(DependencyException.class, this::warm);
                assertTrue(Thread.currentThread().isInterrupted());
            });
            assertTrue(secondRequested.await(5, TimeUnit.SECONDS));
            worker.get().interrupt();
            firstReply.complete(true);
            task.get(5, TimeUnit.SECONDS);

            verifyNoInteractions(mediaLoader, understandingLoader);
            assertReleased();
            assertFalse(firstReply.isCancelled());
        } finally {
            firstReply.complete(true);
            pool.shutdownNow();
        }
    }

    @Test
    void failedAcquisitionResponseSkipsItsLoaderWithoutFillingThatResource() {
        CompletableFuture<Boolean> unavailable = CompletableFuture.failedFuture(
                new RedisConnectionFailureException("async lock unavailable"));
        when(mediaLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong()))
                .thenReturn(new CompletableFutureWrapper<>(unavailable));

        ContentResourceWarmupException failure = assertThrows(ContentResourceWarmupException.class, this::warm);

        assertInstanceOf(ContentResourceLockUnavailableException.class, failure.getCause());
        assertEquals(Map.of(understandingKey, JsonUtil.toJson("understanding")), written);
        verifyNoInteractions(mediaLoader);
        verify(mediaLock, never()).unlockAsync(anyLong());
        verify(understandingLock).unlockAsync(anyLong());
        assertEquals(0, count(MetricNames.CONTENT_CACHE_SOURCE_LOADS, ContentResourceRegion.CONTENT_MEDIA));
    }

    @Test
    void failedUnlockResponseDoesNotPreventOtherReleasesOrRetry() {
        CompletableFuture<Void> unavailable = CompletableFuture.failedFuture(
                new RedisConnectionFailureException("async unlock unavailable"));
        when(mediaLock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>(unavailable));

        assertDoesNotThrow(this::warm);

        verify(mediaLock).unlockAsync(anyLong());
        verify(understandingLock).unlockAsync(anyLong());
        assertFalse(understandingHeld.get());
    }

    @Test
    void existingValuesAreOverwrittenWithoutReadingOrDecodingThem() {
        written.put(mediaKey, JsonUtil.toJson("old-media"));
        written.put(understandingKey, "{broken");

        warm();

        assertEquals(Map.of(mediaKey, JsonUtil.toJson("media"), understandingKey, JsonUtil.toJson("understanding")), written);
        verify(mediaLoader).get();
        verify(understandingLoader).get();
        verifyNoInteractions(values);
        verify(redis).executePipelined(any(RedisCallback.class));
        assertReleased();
    }

    @Test
    void contentionSkipsTheBusyResourceButAllowsTheOtherResourceToWarm() throws Exception {
        when(mediaLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenReturn(new CompletableFutureWrapper<>(false));

        ContentResourceWarmupException failure = assertThrows(ContentResourceWarmupException.class, this::warm);
        assertInstanceOf(ContentResourceBusyException.class, failure.getCause());

        verifyNoInteractions(mediaLoader);
        verify(understandingLoader).get();
        verify(understandingLock).unlockAsync(anyLong());
        verify(values, never()).get(mediaKey);
        assertEquals(Map.of(understandingKey, JsonUtil.toJson("understanding")), written);
        verify(mediaLock, never()).unlockAsync(anyLong());
    }

    @Test
    void contentionOnTheSecondResourceStillFlushesTheFirstSuccessfulLoad() throws Exception {
        when(understandingLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenReturn(new CompletableFutureWrapper<>(false));

        ContentResourceWarmupException failure = assertThrows(ContentResourceWarmupException.class, this::warm);
        assertInstanceOf(ContentResourceBusyException.class, failure.getCause());

        assertEquals(Map.of(mediaKey, JsonUtil.toJson("media")), written);
        verifyNoInteractions(understandingLoader);
        verify(mediaLock).unlockAsync(anyLong());
        verify(understandingLock, never()).unlockAsync(anyLong());
    }

    @Test
    void lockFailureSkipsItsLoaderAndDoesNotChangeOtherWrites() {
        when(redisson.getLock("lock:" + mediaKey)).thenThrow(new RedisConnectionFailureException("lock unavailable"));

        ContentResourceWarmupException failure = assertThrows(ContentResourceWarmupException.class, this::warm);

        assertInstanceOf(ContentResourceLockUnavailableException.class, failure.getCause());
        verifyNoInteractions(mediaLoader, mediaLock);
        assertEquals(Map.of(understandingKey, JsonUtil.toJson("understanding")), written);
        verify(understandingLock).unlockAsync(anyLong());
        assertEquals(0, count(MetricNames.CONTENT_CACHE_SOURCE_LOADS, ContentResourceRegion.CONTENT_MEDIA));
    }

    @Test
    void allLockFailuresSkipEveryLoaderAndLeaveExistingEntriesUntouched() {
        written.put(mediaKey, JsonUtil.toJson("existing-media"));
        written.put(understandingKey, JsonUtil.toJson("existing-understanding"));
        when(redisson.getLock(anyString())).thenThrow(new RedisConnectionFailureException("redis unavailable"));

        ContentResourceWarmupException failure = assertThrows(ContentResourceWarmupException.class, this::warm);

        assertInstanceOf(ContentResourceLockUnavailableException.class, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(Map.of(mediaKey, JsonUtil.toJson("existing-media"),
                understandingKey, JsonUtil.toJson("existing-understanding")), written);
        verifyNoInteractions(mediaLoader, understandingLoader, mediaLock, understandingLock, values);
        verify(redis, never()).executePipelined(any(RedisCallback.class));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_SOURCE_LOADS, ContentResourceRegion.CONTENT_MEDIA));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_SOURCE_LOADS, ContentResourceRegion.UNDERSTANDING));
    }

    @Test
    void failedRefreshKeepsTheExistingValueWhileTheOtherResourceIsUpdated() {
        written.put(mediaKey, JsonUtil.toJson("existing-media"));
        written.put(understandingKey, JsonUtil.toJson("existing-understanding"));
        var loadFailure = new IllegalStateException("media query failed");
        when(mediaLoader.get()).thenThrow(loadFailure);

        ContentResourceWarmupException failure = assertThrows(ContentResourceWarmupException.class, this::warm);

        assertSame(loadFailure, failure.getCause());
        assertEquals(Map.of(mediaKey, JsonUtil.toJson("existing-media"),
                understandingKey, JsonUtil.toJson("understanding")), written);
        verifyNoInteractions(values);
        assertReleased();
    }

    @Test
    void interruptionSkipsTheLoadButReleasesAcquiredLocksAndPreservesTheFlag() {
        when(mediaLock.tryLockAsync(eq(0L), eq(-1L), eq(TimeUnit.MILLISECONDS), anyLong())).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            mediaHeld.set(true);
            return new CompletableFutureWrapper<>(true);
        });
        try {
            assertThrows(DependencyException.class, this::warm);

            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(mediaLoader, understandingLoader);
            assertReleased();
            verify(redis, never()).executePipelined(any(RedisCallback.class));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void loaderFailureKeepsSuccessfulWritesAndAlwaysReleasesLocks() {
        var failure = new IllegalStateException("understanding unavailable");
        when(understandingLoader.get()).thenThrow(failure);

        ContentResourceWarmupException observed = assertThrows(ContentResourceWarmupException.class, this::warm);
        assertSame(failure, observed.getCause());

        assertEquals(Map.of(mediaKey, JsonUtil.toJson("media")), written);
        assertReleased();
        assertEquals(1, count(MetricNames.CONTENT_CACHE_SOURCE_LOAD_FAILED, ContentResourceRegion.UNDERSTANDING));
    }

    @Test
    void reportsTheFailureOnlyAfterSuccessfulWritesAndAllUnlockResponses() throws Exception {
        var loadFailure = new IllegalStateException("understanding unavailable");
        when(understandingLoader.get()).thenThrow(loadFailure);
        var unlockReply = new CompletableFuture<Void>();
        var unlockRequested = new CountDownLatch(1);
        when(understandingLock.unlockAsync(anyLong())).thenAnswer(call -> {
            unlockRequested.countDown();
            return new CompletableFutureWrapper<>(unlockReply.thenRun(() -> understandingHeld.set(false)));
        });
        ExecutorService parent = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = parent.submit(this::warm);
            assertTrue(unlockRequested.await(5, TimeUnit.SECONDS));
            assertFalse(task.isDone(), "加载失败也必须等解锁完成后才能对外抛出");
            assertEquals(Map.of(mediaKey, JsonUtil.toJson("media")), written);
            unlockReply.complete(null);

            ExecutionException observed = assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
            ContentResourceWarmupException failure = assertInstanceOf(ContentResourceWarmupException.class, observed.getCause());
            assertEquals(ContentResourceRegion.UNDERSTANDING, failure.getRegion());
            assertSame(loadFailure, failure.getCause());
            assertReleased();
        } finally {
            unlockReply.complete(null);
            parent.shutdownNow();
        }
    }

    @Test
    void preservesEveryResourceFailureWithoutRequiringASecondRead() {
        var mediaFailure = new IllegalStateException("media unavailable");
        var understandingFailure = new IllegalArgumentException("bad understanding");
        when(mediaLoader.get()).thenThrow(mediaFailure);
        when(understandingLoader.get()).thenThrow(understandingFailure);

        ContentResourceWarmupException failure = assertThrows(ContentResourceWarmupException.class, this::warm);

        assertEquals(ContentResourceRegion.CONTENT_MEDIA, failure.getRegion());
        assertSame(mediaFailure, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        ContentResourceWarmupException other = assertInstanceOf(ContentResourceWarmupException.class, failure.getSuppressed()[0]);
        assertEquals(ContentResourceRegion.UNDERSTANDING, other.getRegion());
        assertSame(understandingFailure, other.getCause());
        verify(redis, never()).executePipelined(any(RedisCallback.class));
        assertReleased();
    }

    @Test
    void pipelineFailureIsBestEffortAndCountsUnconfirmedFillsWithoutRetrying() {
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("pipeline unavailable"));

        assertDoesNotThrow(this::warm);

        assertReleased();
        verify(redis).executePipelined(any(RedisCallback.class));
        assertEquals(1, count(MetricNames.CONTENT_CACHE_FILL_FAILED, ContentResourceRegion.CONTENT_MEDIA));
        assertEquals(1, count(MetricNames.CONTENT_CACHE_FILL_FAILED, ContentResourceRegion.UNDERSTANDING));
        assertEquals(0, count(MetricNames.CONTENT_CACHE_FILLS, ContentResourceRegion.CONTENT_MEDIA));
    }

    @Test
    void refreshesBadEntriesWithDistinctNegativeAndLiteralNullPayloads() {
        written.put(mediaKey, "{broken");
        when(mediaLoader.get()).thenReturn(null);
        when(understandingLoader.get()).thenReturn("null");

        prepare();

        assertEquals(Map.of(mediaKey, "null", understandingKey, JsonUtil.toJson("null")), written);
        assertTtl(mediaKey, 2400, 3000);
        assertTtl(understandingKey, 1440000, 1800000);
        assertReleased();
    }

    private void warm() {
        prepare();
    }

    private void prepare() {
        prepare(Runnable::run);
    }

    private void prepare(Executor executor) {
        ContentResourceCache.WarmupResult result = cache.warmupTogether(executor,
                new WarmupResource<>(ContentResourceRegion.CONTENT_MEDIA, id, mediaLoader, FillPolicy.STANDARD),
                new WarmupResource<>(ContentResourceRegion.UNDERSTANDING, id, understandingLoader, FillPolicy.STANDARD));
        if (result.failure() != null) {
            throw result.failure();
        }
    }

    private void assertReleased() {
        assertFalse(mediaHeld.get());
        assertFalse(understandingHeld.get());
        verify(mediaLock).unlockAsync(anyLong());
        verify(understandingLock).unlockAsync(anyLong());
    }

    private void assertTtl(String key, long minimumMillis, long maximumMillis) {
        long millis = ttls.get(key).toMillis();
        assertTrue(millis >= minimumMillis && millis <= maximumMillis, "ttl=" + millis);
    }

    private double count(String name, ContentResourceRegion region) {
        Counter counter = metrics.find(name).tags("region", region.name(), "policy", FillPolicy.STANDARD.name()).counter();
        return counter == null ? 0 : counter.count();
    }
}

package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentResourceCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final ContentResourceCacheProperties properties = new ContentResourceCacheProperties();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ContentResourceCache cache = new ContentResourceCache(redis, redisson, properties, metrics);
    @SuppressWarnings("unchecked")
    private final Supplier<String> loader = mock(Supplier.class);
    private final String key = ContentResourceRegion.PLAYLIST.key("prepared/1/index.m3u8");

    @BeforeEach
    void setup() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS)).thenReturn(true);
        when(loader.get()).thenReturn("#EXTM3U\n");
    }

    @Test
    void hitDoesNotAcquireLockLoadOrRenewTtl() {
        when(values.get(key)).thenReturn(JsonUtil.toJson("#EXTM3U\n"));

        assertEquals("#EXTM3U\n", get());

        verifyNoInteractions(redisson, lock, loader);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void secondCheckUsesValueFilledWhileWaitingForTheLock() {
        when(values.get(key)).thenReturn(null, JsonUtil.toJson("loaded-by-another-instance"));

        assertEquals("loaded-by-another-instance", get());

        verify(redisson).getLock("lock:" + key);
        verifyNoInteractions(loader);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(lock).unlock();
    }

    @Test
    void missLoadsAndWritesAnArtifactWithinItsTtlUpperBound() {
        assertEquals("#EXTM3U\n", get());

        verify(loader).get();
        verify(values).set(eq(key), eq(JsonUtil.toJson("#EXTM3U\n")),
                argThat(ttl -> within(ttl, 1_440_000, 1_800_000)));
        verify(lock).unlock();
    }

    @Test
    void stateUsesItsOwnShortTtlAndRoundTripsDomainEnumsAndLongIds() {
        String stateKey = ContentResourceRegion.CONTENT_STATE.key("1");
        TestState state = new TestState(9007199254740993L, Visibility.PRIVATE);
        cache.get(ContentResourceRegion.CONTENT_STATE, "1", TestState.class, () -> state);
        verify(values).set(eq(stateKey), eq(JsonUtil.toJson(state)),
                argThat(ttl -> within(ttl, 8000, 10000)));

        when(values.get(stateKey)).thenReturn(JsonUtil.toJson(state));
        assertEquals(state, cache.get(ContentResourceRegion.CONTENT_STATE, "1", TestState.class,
                () -> fail("cache hit must not load")));
    }

    @Test
    void confirmedMissingResourceGetsAShortNegativeCacheEntry() {
        when(loader.get()).thenReturn(null);

        assertNull(get());

        verify(values).set(eq(key), eq("null"), argThat(ttl -> within(ttl, 2400, 3000)));
        verify(lock).unlock();
    }

    @Test
    void negativeHitDoesNotLoadOrAcquireLock() {
        when(values.get(key)).thenReturn("null");

        assertNull(get());

        verifyNoInteractions(redisson, lock, loader);
    }

    @Test
    void literalNullTextIsDifferentFromAMissingResource() {
        when(values.get(key)).thenReturn(JsonUtil.toJson("null"));
        assertEquals("null", get());
        verifyNoInteractions(loader);
    }

    @Test
    void loaderFailureIsPropagatedWithoutCachingAndStillReleasesTheLock() {
        RuntimeException failure = new IllegalStateException("storage unavailable");
        when(loader.get()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, this::get));

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(lock).unlock();
    }

    @Test
    void malformedPayloadIsRebuiltUnderTheLockWithoutAnUnlockedDelete() {
        when(values.get(key)).thenReturn("{broken");

        assertEquals("#EXTM3U\n", get());

        verify(loader).get();
        verify(redis, never()).delete(anyString());
        verify(lock).unlock();
    }

    @Test
    void emptyPayloadIsAMissRatherThanANegativeHit() {
        when(values.get(key)).thenReturn("");
        assertEquals("#EXTM3U\n", get());
        verify(loader).get();
    }

    @Test
    void invalidDateFromCustomAdapterIsRebuiltUnderTheLock() {
        String datedKey = ContentResourceRegion.UNDERSTANDING.key("1");
        when(values.get(datedKey)).thenReturn("{\"updatedAt\":\"not-a-date\"}");
        DatedValue fresh = new DatedValue(LocalDateTime.of(2026, 11, 2, 12, 0));

        assertEquals(fresh, cache.get(ContentResourceRegion.UNDERSTANDING, "1", DatedValue.class, () -> fresh));

        verify(values).set(eq(datedKey), eq(JsonUtil.toJson(fresh)), any(Duration.class));
        verify(lock).unlock();
    }

    @Test
    void redisReadFailureLoadsDirectlyWithoutTryingTheLockOrWritingCache() {
        when(values.get(key)).thenThrow(new RedisConnectionFailureException("read failed"));

        assertEquals("#EXTM3U\n", get());

        verify(loader).get();
        verifyNoInteractions(redisson, lock);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void lockCreationFailureLoadsDirectlyWithoutWritingCache() {
        when(redisson.getLock(anyString())).thenThrow(new RedisException("client unavailable"));

        assertEquals("#EXTM3U\n", get());

        verify(loader).get();
        verifyNoInteractions(lock);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void lockAcquisitionFailureLoadsDirectlyWithoutUnlockingOrWritingCache() throws Exception {
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS))
                .thenThrow(new RedisException("lock unavailable"));

        assertEquals("#EXTM3U\n", get());

        verify(loader).get();
        verify(lock, never()).unlock();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void secondReadFailureLoadsOnceAndReleasesTheAcquiredLock() {
        when(values.get(key)).thenReturn(null).thenThrow(new RedisConnectionFailureException("second read failed"));

        assertEquals("#EXTM3U\n", get());

        verify(loader).get();
        verify(lock).unlock();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void finalReadFailureAfterLockTimeoutLoadsDirectly() throws Exception {
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS)).thenReturn(false);
        when(values.get(key)).thenReturn(null).thenThrow(new RedisConnectionFailureException("last read failed"));

        assertEquals("#EXTM3U\n", get());

        verify(loader).get();
        verify(lock, never()).unlock();
    }

    @Test
    void failedCacheWritePreservesTheSuccessfulSourceResult() {
        doThrow(new RedisConnectionFailureException("write failed"))
                .when(values).set(anyString(), anyString(), any(Duration.class));

        assertEquals("#EXTM3U\n", get());

        verify(loader).get();
        verify(lock).unlock();
    }

    @Test
    void failedCacheEncodingPreservesTheSuccessfulSourceResult() {
        assertTrue(cache.get(ContentResourceRegion.UNDERSTANDING, "1", Double.class, () -> Double.NaN).isNaN());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(lock).unlock();
    }

    @Test
    void sourceFailureDuringRedisFallbackIsPropagatedOnce() {
        when(values.get(key)).thenThrow(new RedisConnectionFailureException("read failed"));
        RuntimeException sourceFailure = new IllegalStateException("source unavailable");
        when(loader.get()).thenThrow(sourceFailure);

        assertSame(sourceFailure, assertThrows(IllegalStateException.class, this::get));

        verify(loader).get();
        verifyNoInteractions(redisson, lock);
    }

    @Test
    void sourceFailureDuringLockFallbackIsPropagatedOnce() throws Exception {
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS))
                .thenThrow(new RedisException("lock unavailable"));
        RuntimeException sourceFailure = new IllegalStateException("source unavailable");
        when(loader.get()).thenThrow(sourceFailure);

        assertSame(sourceFailure, assertThrows(IllegalStateException.class, this::get));

        verify(loader).get();
        verify(lock, never()).unlock();
    }

    @Test
    void concreteDtoPreservesItsGenericCollectionElementTypes() {
        DatedValue value = new DatedValue(LocalDateTime.of(2026, 11, 2, 12, 0));
        DatedValues expected = new DatedValues(List.of(value));
        String collectionKey = ContentResourceRegion.UNDERSTANDING.key("1");
        when(values.get(collectionKey)).thenReturn(JsonUtil.toJson(expected));

        DatedValues actual = cache.get(ContentResourceRegion.UNDERSTANDING, "1", DatedValues.class,
                () -> fail("concrete DTO must deserialize without loading"));

        assertEquals(value, actual.items().get(0));
        verifyNoInteractions(redisson, lock);
    }

    @Test
    void evictionOnlyDeletesTheRequestedResource() {
        cache.evict(ContentResourceRegion.PLAYLIST, "prepared/1/index.m3u8");
        verify(redis).delete(key);
        verifyNoInteractions(redisson, lock, loader);
    }

    @Test
    void evictingAnAbsentEntryIsAlsoSuccessful() {
        when(redis.delete(key)).thenReturn(false);
        assertDoesNotThrow(() -> cache.evict(ContentResourceRegion.PLAYLIST, "prepared/1/index.m3u8"));
        assertNull(metrics.find(MetricNames.CONTENT_CACHE_EVICT_FAILED).counter());
    }

    @Test
    void failedEvictionReportsFailureWithoutThrowing() {
        when(redis.delete(key)).thenThrow(new RedisConnectionFailureException("delete failed"));
        assertDoesNotThrow(() -> cache.evict(ContentResourceRegion.PLAYLIST, "prepared/1/index.m3u8"));
        assertEquals(1, metrics.get(MetricNames.CONTENT_CACHE_EVICT_FAILED).counter().count());
    }

    @Test
    void failureMetricsCannotMakeEvictionThrow() {
        MeterRegistry unavailable = mock(MeterRegistry.class);
        when(unavailable.counter(MetricNames.CONTENT_CACHE_EVICT_FAILED))
                .thenThrow(new IllegalStateException("metrics unavailable"));
        when(redis.delete(key)).thenThrow(new RedisConnectionFailureException("delete failed"));
        var measuredCache = new ContentResourceCache(redis, redisson, properties, unavailable);

        assertDoesNotThrow(() -> measuredCache.evict(ContentResourceRegion.PLAYLIST, "prepared/1/index.m3u8"));
        verify(unavailable).counter(MetricNames.CONTENT_CACHE_EVICT_FAILED);
    }

    @Test
    void configuredLockWaitIsUsedInsteadOfTheDefault() throws Exception {
        properties.setLockWaitMillis(123);
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS)).thenReturn(true);

        assertEquals("#EXTM3U\n", get());

        verify(lock).tryLock(123, TimeUnit.MILLISECONDS);
    }

    @Test
    void lockTimeoutChecksCacheOnceMoreButDoesNotFallThroughToTheLoader() throws Exception {
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS)).thenReturn(false);

        DependencyException failure = assertThrows(DependencyException.class, this::get);

        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.getErrorCode());
        verify(values, times(2)).get(key);
        verifyNoInteractions(loader);
        verify(lock, never()).unlock();
    }

    @Test
    void lockTimeoutCanUseAValueCompletedAtTheDeadline() throws Exception {
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS)).thenReturn(false);
        when(values.get(key)).thenReturn(null, JsonUtil.toJson("just-completed"));

        assertEquals("just-completed", get());

        verifyNoInteractions(loader);
        verify(lock, never()).unlock();
    }

    @Test
    void lockTimeoutCanUseANegativeEntryCompletedAtTheDeadline() throws Exception {
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS)).thenReturn(false);
        when(values.get(key)).thenReturn(null, "null");

        assertNull(get());

        verifyNoInteractions(loader);
        verify(lock, never()).unlock();
    }

    @Test
    void interruptionRestoresTheFlagAndDoesNotStartALoader() throws Exception {
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        try {
            assertThrows(DependencyException.class, this::get);
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(loader);
            verify(lock, never()).unlock();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unlockFailureDoesNotHideTheOriginalLoaderFailure() {
        RuntimeException failure = new IllegalStateException("database unavailable");
        when(loader.get()).thenThrow(failure);
        doThrow(new IllegalMonitorStateException("ownership lost")).when(lock).unlock();

        assertSame(failure, assertThrows(IllegalStateException.class, this::get));
    }

    @Test
    void regionsAndResourceIdsDoNotShareKeys() {
        assertNotEquals(ContentResourceRegion.CONTENT_STATE.key("1"), ContentResourceRegion.AUDIO_STATE.key("1"));
        assertNotEquals(ContentResourceRegion.PLAYLIST.key("1"), ContentResourceRegion.PLAYLIST.key("2"));
        assertEquals(ContentResourceRegion.TtlGroup.ARTIFACT, ContentResourceRegion.CONTENT_MEDIA.ttlGroup());
        assertEquals(ContentResourceRegion.TtlGroup.STATE, ContentResourceRegion.AUDIO_STATE.ttlGroup());
    }

    @Test
    void batchHitsUseOneMultiGetIncludingNegativeEntriesAndLiteralNullStrings() {
        List<String> keys = List.of(ContentResourceRegion.CONTENT_STATE.key("1"),
                ContentResourceRegion.CONTENT_MEDIA.key("1"), ContentResourceRegion.UNDERSTANDING.key("1"));
        when(values.multiGet(keys)).thenReturn(List.of(JsonUtil.toJson("state"), "null", JsonUtil.toJson("null")));

        ContentResourceReader reader = cache.readTogether("1", ContentResourceRegion.CONTENT_STATE,
                ContentResourceRegion.CONTENT_MEDIA, ContentResourceRegion.UNDERSTANDING);

        assertEquals("state", reader.get(ContentResourceRegion.CONTENT_STATE, "1", String.class, loader));
        assertNull(reader.get(ContentResourceRegion.CONTENT_MEDIA, "1", String.class, loader));
        assertEquals("null", reader.get(ContentResourceRegion.UNDERSTANDING, "1", String.class, loader));
        verify(values).multiGet(keys);
        verify(values, never()).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(redisson, loader);
        assertNull(metrics.find(MetricNames.CONTENT_CACHE_BATCH_READ_FAILED).counter());
    }

    @Test
    void batchMissRechecksRedisAfterAcquiringTheLock() {
        String mediaKey = ContentResourceRegion.CONTENT_MEDIA.key("1");
        when(values.multiGet(List.of(mediaKey))).thenReturn(Arrays.asList((String) null));
        when(values.get(mediaKey)).thenReturn(JsonUtil.toJson("filled-while-waiting"));

        ContentResourceReader reader = cache.readTogether("1", ContentResourceRegion.CONTENT_MEDIA);

        assertEquals("filled-while-waiting", reader.get(ContentResourceRegion.CONTENT_MEDIA, "1", String.class, loader));
        verify(values).get(mediaKey);
        verify(lock).unlock();
        verifyNoInteractions(loader);
    }

    @Test
    void batchLoadsOnlyMissingItemsAndKeepsTheOtherHits() {
        String mediaKey = ContentResourceRegion.CONTENT_MEDIA.key("1");
        String stateKey = ContentResourceRegion.CONTENT_STATE.key("1");
        when(values.multiGet(List.of(stateKey, mediaKey))).thenReturn(Arrays.asList(JsonUtil.toJson("state"), null));

        ContentResourceReader reader = cache.readTogether("1", ContentResourceRegion.CONTENT_STATE, ContentResourceRegion.CONTENT_MEDIA);

        assertEquals("state", reader.get(ContentResourceRegion.CONTENT_STATE, "1", String.class, loader));
        assertEquals("#EXTM3U\n", reader.get(ContentResourceRegion.CONTENT_MEDIA, "1", String.class, loader));
        verify(loader).get();
        verify(values).get(mediaKey);
        verify(values, never()).get(stateKey);
        verify(values).set(eq(mediaKey), eq(JsonUtil.toJson("#EXTM3U\n")), any(Duration.class));
    }

    @Test
    void malformedBatchPayloadIsRebuiltUnderTheLock() {
        String stateKey = ContentResourceRegion.CONTENT_STATE.key("1");
        when(values.multiGet(List.of(stateKey))).thenReturn(List.of("{\"updatedAt\":\"bad-date\"}"));
        DatedValue expected = new DatedValue(LocalDateTime.of(2026, 1, 1, 0, 0));

        ContentResourceReader reader = cache.readTogether("1", ContentResourceRegion.CONTENT_STATE);

        assertEquals(expected, reader.get(ContentResourceRegion.CONTENT_STATE, "1", DatedValue.class, () -> expected));
        verify(values).get(stateKey);
        verify(values).set(eq(stateKey), eq(JsonUtil.toJson(expected)), any(Duration.class));
        verify(lock).unlock();
    }

    @Test
    void batchReadFailureLoadsFromSourceWithoutLockingOrFilling() {
        when(values.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("unavailable"));
        ContentResourceReader reader = cache.readTogether("1", ContentResourceRegion.CONTENT_STATE, ContentResourceRegion.CONTENT_MEDIA);

        assertEquals("#EXTM3U\n", reader.get(ContentResourceRegion.CONTENT_STATE, "1", String.class, loader));
        assertEquals("#EXTM3U\n", reader.get(ContentResourceRegion.CONTENT_MEDIA, "1", String.class, loader));

        assertEquals(1, metrics.get(MetricNames.CONTENT_CACHE_BATCH_READ_FAILED).counter().count());
        verify(values).multiGet(anyCollection());
        verify(loader, times(2)).get();
        verify(values, never()).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(redisson);
    }

    @Test
    void failureMetricsCannotPreventBatchReadFallback() {
        MeterRegistry unavailable = mock(MeterRegistry.class);
        when(unavailable.counter(MetricNames.CONTENT_CACHE_BATCH_READ_FAILED))
                .thenThrow(new IllegalStateException("metrics unavailable"));
        when(values.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("unavailable"));
        var measuredCache = new ContentResourceCache(redis, redisson, properties, unavailable);

        ContentResourceReader reader = measuredCache.readTogether("1", ContentResourceRegion.CONTENT_MEDIA);

        assertEquals("#EXTM3U\n", reader.get(ContentResourceRegion.CONTENT_MEDIA, "1", String.class, loader));
        verify(unavailable).counter(MetricNames.CONTENT_CACHE_BATCH_READ_FAILED);
        verify(loader).get();
        verify(values, never()).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(redisson);
    }

    @Test
    void keysOutsideTheBatchUseTheRegularCachePath() {
        String stateKey = ContentResourceRegion.CONTENT_STATE.key("1");
        when(values.multiGet(List.of(stateKey))).thenReturn(List.of(JsonUtil.toJson("state")));
        when(values.get(key)).thenReturn(JsonUtil.toJson("playlist"));
        ContentResourceReader reader = cache.readTogether("1", ContentResourceRegion.CONTENT_STATE);

        assertEquals("playlist", reader.get(ContentResourceRegion.PLAYLIST, "prepared/1/index.m3u8", String.class, loader));

        verify(values).get(key);
        verifyNoInteractions(redisson, loader);
    }

    private String get() {
        return cache.get(ContentResourceRegion.PLAYLIST, "prepared/1/index.m3u8", String.class, loader);
    }

    private static boolean within(Duration value, long minimumMillis, long maximumMillis) {
        long millis = value.toMillis();
        return millis >= minimumMillis && millis <= maximumMillis;
    }

    private enum Visibility { PUBLIC, PRIVATE }

    private record TestState(long authorId, Visibility visibility) {}

    private record DatedValue(LocalDateTime updatedAt) {}

    private record DatedValues(List<DatedValue> items) {}
}

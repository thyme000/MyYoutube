package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.media.ContentArtifacts;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCacheProperties;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceInvalidator;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceRegion;
import cn.diaojiahong.myyoutube.content.domain.ContentUnderstandingResource;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentReviewState;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper;
import com.google.gson.JsonParseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentUnderstandingResourcesTest {
    private final ContentUnderstandingMapper rows = mock(ContentUnderstandingMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final ContentResourceCacheProperties properties = new ContentResourceCacheProperties();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ContentResourceCache cache = new ContentResourceCache(redis, redisson, properties, metrics);
    private final ContentUnderstandingResources resources = new ContentUnderstandingResources(cache,
            new ContentUnderstandingResourceLoader(rows));
    private final Map<String, String> entries = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private final String key = ContentResourceRegion.UNDERSTANDING.key("1");
    private final ContentUnderstanding row = new ContentUnderstanding().setContentId(1L).setReceived(true)
            .setState(ContentReviewState.AUTO_PENDING).setReviewAttempt(1).setInputJson("input-not-for-display")
            .setSummary("完整摘要").setChaptersJson(JsonUtil.toJson(List.of(new ContentArtifacts.Chapter(1000, "第一章"))))
            .setSubtitleKey("subtitles/one.vtt");

    @BeforeEach
    void setup() throws Exception {
        when(rows.selectById(1L)).thenReturn(row);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(call -> entries.remove(call.getArgument(0)) != null);
        doAnswer(call -> {
            entries.put(call.getArgument(0), call.getArgument(1));
            ttls.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS)).thenReturn(true);
    }

    @AfterEach
    void close() {
        metrics.close();
    }

    @Test
    void warmResourceUsesOneRedisReadAndPreservesTypedChapters() {
        resources.get(1L);
        clearInvocations(values, redisson, lock);

        ContentUnderstandingResource cached = resources.get(1L);

        assertEquals("完整摘要", cached.summary().summary());
        assertEquals(new ContentArtifacts.Chapter(1000, "第一章"), cached.summary().chapters().get(0));
        assertEquals("subtitles/one.vtt", cached.subtitleKey());
        verify(rows).selectById(1L);
        verify(values).get(key);
        verifyNoMoreInteractions(values);
        verifyNoInteractions(redisson, lock);
        assertTrue(ttls.get(key).toMillis() >= 1440000 && ttls.get(key).toMillis() <= 1800000);
    }

    @Test
    void reviewProgressIsExcludedFromTheCachedResource() {
        resources.get(1L);
        row.setState(ContentReviewState.APPROVED).setReviewAttempt(2);
        assertEquals("完整摘要", resources.get(1L).summary().summary());
        verify(rows).selectById(1L);
        String payload = entries.get(key);
        assertFalse(payload.contains("state"));
        assertFalse(payload.contains("reviewAttempt"));
        assertFalse(payload.contains("inputJson"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = false)
    void unfinishedRowsOnlyUseTheShortNegativeTtl(Boolean received) {
        row.setReceived(received).setChaptersJson("not-ready-json");
        assertNull(resources.get(1L));
        assertNull(resources.get(1L));
        verify(rows).selectById(1L);
        assertEquals("null", entries.get(key));
        assertTrue(ttls.get(key).toMillis() >= 2400 && ttls.get(key).toMillis() <= 3000);
    }

    @Test
    void missingRowsAreNegativelyCached() {
        when(rows.selectById(1L)).thenReturn(null);
        assertNull(resources.get(1L));
        assertNull(resources.get(1L));
        verify(rows).selectById(1L);
        assertEquals("null", entries.get(key));
    }

    @Test
    void emptySummaryAndAbsentChaptersRemainACompletedResource() {
        row.setSummary("").setChaptersJson(null).setSubtitleKey(null);
        ContentUnderstandingResource resource = resources.get(1L);
        assertEquals("", resource.summary().summary());
        assertTrue(resource.summary().chapters().isEmpty());
        assertNull(resource.subtitleKey());
        assertTrue(ttls.get(key).toMillis() > 3000);
    }

    @Test
    void completionInvalidationRemovesAnEarlierNegativeEntry() {
        row.setReceived(false);
        assertNull(resources.get(1L));
        row.setReceived(true);
        assertNull(resources.get(1L));

        var invalidator = new ContentResourceInvalidator(cache, mock(TaskScheduler.class), properties, metrics);
        invalidator.evictAfterCommit(ContentResourceRegion.UNDERSTANDING, "1");

        assertEquals("完整摘要", resources.get(1L).summary().summary());
        verify(rows, times(2)).selectById(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "{\"summary\":{\"chapters\":null}}"})
    void malformedCacheEntriesAreReloaded(String payload) {
        entries.put(key, payload);
        assertEquals("完整摘要", resources.get(1L).summary().summary());
        verify(rows).selectById(1L);
    }

    @Test
    void failedDatabaseLoadsDoNotBecomeNegativeEntries() {
        when(rows.selectById(1L)).thenThrow(new QueryTimeoutException("slow query")).thenReturn(row);
        assertThrows(QueryTimeoutException.class, () -> resources.get(1L));
        assertTrue(entries.isEmpty());
        assertEquals("完整摘要", resources.get(1L).summary().summary());
    }

    @Test
    void invalidStoredChaptersAreNotCachedAsMissingResources() {
        row.setChaptersJson("{");
        assertThrows(JsonParseException.class, () -> resources.get(1L));
        assertTrue(entries.isEmpty());
    }

    @Test
    void redisFailureLoadsTheResourceWithoutFillingOrLocking() {
        when(values.get(key)).thenThrow(new RedisConnectionFailureException("offline"));
        assertEquals("完整摘要", resources.get(1L).summary().summary());
        assertEquals("完整摘要", resources.get(1L).summary().summary());
        verify(rows, times(2)).selectById(1L);
        verifyNoInteractions(redisson, lock);
        assertTrue(entries.isEmpty());
    }
}

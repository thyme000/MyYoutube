package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Timeout(5)
class HotSearchAsyncTest {
    final HotSearchStore store = mock(HotSearchStore.class);

    final HotSearchHistoryStore history = mock(HotSearchHistoryStore.class);

    final HotSearchProperties properties = new HotSearchProperties();

    final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    final HotSearchMetrics metrics = new HotSearchMetrics(registry);

    final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = new HotSearchConfiguration()
            .hotSearchCacheExecutor();

    HotSearchService service() throws Exception {
        return new HotSearchService(
                store,
                history,
                mock(HotSearchModel.class),
                new HotSearchFilter(properties),
                properties,
                mock(IdempotentGuard.class), executor, Clock.systemUTC(), metrics);
    }

    @AfterEach
    void close() {
        executor.shutdown();
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 7, 10})
    void readsOnlineSnapshotUsingTheConfiguredTopLimit(int limit) throws Exception {
        properties.setTopLimit(limit);
        List<String> names = IntStream.range(0, 12).mapToObj(i -> "正常主题" + i).toList();
        when(store.hot()).thenReturn(names);

        assertEquals(names.subList(0, limit), service().hot());
    }

    @Test
    void recordingNeverWaitsForRedisAndNoiseNeverReachesIt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(store.record(any())).thenAnswer(call -> {
            entered.countDown();
            release.await();
            return true;
        });
        HotSearchService service = service();
        try {
            service.record(1L, "1111");
            verifyNoInteractions(store);
            service.record(1L, "正常搜索");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void cacheTimeoutAndExecutorRejectionFallBackWithoutChangingSearch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(store.named("搜索")).thenAnswer(call -> {
            entered.countDown();
            release.await();
            return null;
        });
        HotSearchService service = service();
        try {
            assertNull(service.cachedGroup("搜索", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(1, registry.get(MetricNames.HOT_SEARCH_CACHE_READS)
                    .tags("kind", "named", "outcome", "timeout")
                    .counter()
                    .count());
        } finally {
            release.countDown();
        }
        executor.shutdown();
        assertDoesNotThrow(() -> service.record(1L, "搜索"));
        assertTrue(service.hot().isEmpty());
        assertEquals(1, registry.get(MetricNames.HOT_SEARCH_CACHE_READS)
                .tags("kind", "hot", "outcome", "rejected")
                .counter()
                .count());
    }

    @Test
    void collectionMetricsDistinguishFilteringCapacityRejectionAndRedisFailure() {
        ThreadPoolTaskExecutor direct = mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
        doAnswer(call -> {
            call.getArgument(0, Runnable.class).run();
            return null;
        }).
        when(direct).execute(any(Runnable.class));
        when(store.record(any()))
                .thenReturn(true)
                .thenReturn(false)
                .thenThrow(new IllegalStateException("redis unavailable"));
        var service = new HotSearchService(store, history, mock(HotSearchModel.class), new HotSearchFilter(properties),
                properties, mock(IdempotentGuard.class), direct, Clock.systemUTC(), metrics);

        service.record(1L, "正常搜索");
        service.record(1L, "正常搜索");
        service.record(1L, "正常搜索");
        service.record(1L, "1111");
        doThrow(new java.util.concurrent.RejectedExecutionException()).when(direct).execute(any(Runnable.class));
        assertDoesNotThrow(() -> service.record(1L, "正常搜索"));

        for (String outcome : new String[]{"accepted", "capacity", "failure", "filtered", "rejected"})
            assertEquals(
                    1,
                    registry.get(MetricNames.HOT_SEARCH_RECORDS).tag("outcome", outcome).counter().count(),
                    outcome);
        verify(store, times(3)).record(any());
        verifyNoInteractions(history);
    }

    @Test
    void recordsTheExactInputSeparatelyFromGroupingTextWithoutCallingTheDatabase() throws Exception {
        var recorded = new CountDownLatch(1);
        when(store.record(any())).thenAnswer(call -> {
            recorded.countDown();
            return true;
        });
        String raw = ((char) 1) + "  C++ 入门  " + ((char) 1);
        service().record(7L, raw);
        assertTrue(recorded.await(1, TimeUnit.SECONDS));
        ArgumentCaptor<HotSearchStore.Event> events = org.mockito.ArgumentCaptor.forClass(HotSearchStore.Event.class);
        verify(store).record(events.capture());
        assertEquals(raw, events.getValue().rawKeyword());
        assertEquals("C++ 入门", events.getValue().keyword());
        verifyNoInteractions(history);
    }

    @Test
    void oversizedRawInputOnlySkipsHeatCollection() throws Exception {
        service().record(7L, " ".repeat(1024) + "Java");
        verifyNoInteractions(store, history);
    }
}

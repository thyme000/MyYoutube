package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Timeout(5)
class HotSearchAsyncTest {
    final HotSearchStore store = mock(HotSearchStore.class);
    final HotSearchHistoryStore history = mock(HotSearchHistoryStore.class);
    final HotSearchProperties properties = new HotSearchProperties();
    final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = new HotSearchConfiguration().hotSearchCacheExecutor();
    HotSearchService service() throws Exception {
        return new HotSearchService(store, history, mock(HotSearchModel.class), new HotSearchFilter(properties), properties,
                mock(IdempotentGuard.class), executor, Clock.systemUTC());
    }
    @AfterEach void close() { executor.shutdown(); }

    @ParameterizedTest
    @ValueSource(ints = {3, 7, 10})
    void readsOnlineSnapshotUsingTheConfiguredTopLimit(int limit) throws Exception {
        properties.setTopLimit(limit);
        var names = IntStream.range(0, 12).mapToObj(i -> "正常主题" + i).toList();
        when(store.hot()).thenReturn(names);

        assertEquals(names.subList(0, limit), service().hot());
    }

    @Test void recordingNeverWaitsForRedisAndNoiseNeverReachesIt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(store.record(any())).thenAnswer(call -> { entered.countDown(); release.await(); return true; });
        var service = service();
        try {
            service.record(1L, "1111"); verifyNoInteractions(store);
            service.record(1L, "正常搜索");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test void cacheTimeoutAndExecutorRejectionFallBackWithoutChangingSearch() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(store.named("搜索")).thenAnswer(call -> { entered.countDown(); release.await(); return null; });
        var service = service();
        try {
            assertNull(service.cachedGroup("搜索", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        executor.shutdown();
        assertDoesNotThrow(() -> service.record(1L, "搜索"));
        assertTrue(service.hot().isEmpty());
    }

    @Test void recordsTheExactInputSeparatelyFromGroupingTextWithoutCallingTheDatabase() throws Exception {
        var recorded = new CountDownLatch(1);
        when(store.record(any())).thenAnswer(call -> { recorded.countDown(); return true; });
        String raw = ((char) 1) + "  C++ 入门  " + ((char) 1);
        service().record(7L, raw);
        assertTrue(recorded.await(1, TimeUnit.SECONDS));
        var events = org.mockito.ArgumentCaptor.forClass(HotSearchStore.Event.class);
        verify(store).record(events.capture());
        assertEquals(raw, events.getValue().rawKeyword());
        assertEquals("C++ 入门", events.getValue().keyword());
        verifyNoInteractions(history);
    }

    @Test void oversizedRawInputOnlySkipsHeatCollection() throws Exception {
        service().record(7L, " ".repeat(1024) + "Java");
        verifyNoInteractions(store, history);
    }
}

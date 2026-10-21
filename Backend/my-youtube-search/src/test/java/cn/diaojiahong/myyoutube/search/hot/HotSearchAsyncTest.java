package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Timeout(5)
class HotSearchAsyncTest {
    final HotSearchStore store = mock(HotSearchStore.class);
    final HotSearchProperties properties = new HotSearchProperties();
    final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor = new HotSearchConfiguration().hotSearchCacheExecutor();
    HotSearchService service() throws Exception {
        return new HotSearchService(store, mock(HotSearchModel.class), new HotSearchFilter(properties), properties,
                mock(IdempotentGuard.class), executor, Clock.systemUTC());
    }
    @AfterEach void close() { executor.shutdown(); }

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
}

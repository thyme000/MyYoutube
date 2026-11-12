package cn.diaojiahong.myyoutube.common.counter;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CounterFlushServiceTest {

    private final CounterBuffer buffer = mock(CounterBuffer.class);
    private final CounterTarget target = mock(CounterTarget.class);
    private final CounterFlushService flush = new CounterFlushService(buffer, provider(target));

    @AfterEach
    void shutdown() {
        flush.shutdownFlushExecutor();
    }

    @Test
    void distinctIdsFlushOnMoreThanOneThread() {
        Set<Long> ids = new LinkedHashSet<>();
        for (long id = 1; id <= 8; id++) {
            ids.add(id);
        }
        pending(ids);
        when(buffer.claim(eq(CounterType.CONTENT_LIKE), anyLong())).thenReturn(1L);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        doAnswer(invocation -> {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            Thread.sleep(40);
            inFlight.decrementAndGet();
            return null;
        }).when(target).applyDelta(anyLong(), eq(1L));

        flush.flushRound();

        assertTrue(maxInFlight.get() > 1);
        verify(buffer, times(8)).ack(eq(CounterType.CONTENT_LIKE), anyLong());
    }

    @Test
    void failedApplyLeavesThatIdUnacked() {
        pending(new LinkedHashSet<>(List.of(1L, 2L)));
        when(buffer.claim(eq(CounterType.CONTENT_LIKE), anyLong())).thenReturn(3L);
        doThrow(new IllegalStateException("写入失败")).when(target).applyDelta(2L, 3L);

        flush.flushRound();

        verify(buffer).ack(CounterType.CONTENT_LIKE, 1L);
        verify(buffer, times(0)).ack(CounterType.CONTENT_LIKE, 2L);
    }

    @Test
    void claimFailureStillVisitsEveryId() {
        Set<Long> ids = new LinkedHashSet<>();
        for (long id = 1; id <= 5; id++) {
            ids.add(id);
        }
        pending(ids);
        when(buffer.claim(eq(CounterType.CONTENT_LIKE), anyLong()))
                .thenThrow(new IllegalStateException("redis"));

        flush.flushRound();

        verify(buffer, times(5)).claim(eq(CounterType.CONTENT_LIKE), anyLong());
        verify(buffer, times(0)).ack(eq(CounterType.CONTENT_LIKE), anyLong());
    }

    @Test
    void singleIdClaimFailureStillFlushesLaterTargets() {
        CounterTarget fans = mock(CounterTarget.class);
        CounterTarget attention = mock(CounterTarget.class);
        when(fans.type()).thenReturn(CounterType.USER_FANS);
        when(attention.type()).thenReturn(CounterType.USER_ATTENTION);
        when(buffer.scanIds(CounterKeys.inflightPrefix(CounterType.USER_FANS))).thenReturn(Set.of());
        when(buffer.scanIds(CounterKeys.pendingPrefix(CounterType.USER_FANS))).thenReturn(Set.of(1L));
        when(buffer.scanIds(CounterKeys.inflightPrefix(CounterType.USER_ATTENTION))).thenReturn(Set.of());
        when(buffer.scanIds(CounterKeys.pendingPrefix(CounterType.USER_ATTENTION))).thenReturn(Set.of(2L));
        when(buffer.claim(CounterType.USER_FANS, 1L)).thenThrow(new IllegalStateException("redis"));
        when(buffer.claim(CounterType.USER_ATTENTION, 2L)).thenReturn(4L);

        CounterFlushService both = new CounterFlushService(buffer, provider(fans, attention));
        try {
            both.flushRound();
        } finally {
            both.shutdownFlushExecutor();
        }

        verify(buffer, times(0)).ack(CounterType.USER_FANS, 1L);
        verify(attention).applyDelta(2L, 4L);
        verify(buffer).ack(CounterType.USER_ATTENTION, 2L);
    }

    @Test
    void ackFailureStaysOnThatId() {
        pending(Set.of(1L));
        when(buffer.claim(CounterType.CONTENT_LIKE, 1L)).thenReturn(3L);
        doThrow(new IllegalStateException("redis")).when(buffer).ack(CounterType.CONTENT_LIKE, 1L);

        flush.flushRound();

        verify(target).applyDelta(1L, 3L);
        verify(buffer).ack(CounterType.CONTENT_LIKE, 1L);
    }

    @Test
    void interruptSkipsLaterTargets() {
        CounterTarget fans = mock(CounterTarget.class);
        CounterTarget attention = mock(CounterTarget.class);
        when(fans.type()).thenReturn(CounterType.USER_FANS);
        when(attention.type()).thenReturn(CounterType.USER_ATTENTION);
        when(buffer.scanIds(CounterKeys.inflightPrefix(CounterType.USER_FANS))).thenReturn(Set.of());
        when(buffer.scanIds(CounterKeys.pendingPrefix(CounterType.USER_FANS)))
                .thenReturn(new LinkedHashSet<>(List.of(1L, 2L)));
        when(buffer.scanIds(CounterKeys.inflightPrefix(CounterType.USER_ATTENTION))).thenReturn(Set.of());
        when(buffer.scanIds(CounterKeys.pendingPrefix(CounterType.USER_ATTENTION))).thenReturn(Set.of(3L));
        when(buffer.claim(eq(CounterType.USER_FANS), anyLong())).thenReturn(1L);
        Thread scheduler = Thread.currentThread();
        doAnswer(invocation -> {
            scheduler.interrupt();
            return null;
        }).when(fans).applyDelta(anyLong(), eq(1L));

        CounterFlushService both = new CounterFlushService(buffer, provider(fans, attention));
        try {
            both.flushRound();
        } finally {
            both.shutdownFlushExecutor();
            Thread.interrupted();
        }

        verify(attention, never()).applyDelta(anyLong(), anyLong());
        verify(buffer, never()).ack(eq(CounterType.USER_ATTENTION), anyLong());
    }

    @Test
    void oneRoundStopsAtTheKeyCap() {
        Set<Long> ids = new HashSet<>();
        for (long id = 1; id <= RedisConstants.COUNTER_MAX_KEYS_PER_ROUND + 1; id++) {
            ids.add(id);
        }
        pending(ids);
        when(buffer.claim(eq(CounterType.CONTENT_LIKE), anyLong())).thenReturn(1L);

        flush.flushRound();

        verify(buffer, times(RedisConstants.COUNTER_MAX_KEYS_PER_ROUND))
                .ack(eq(CounterType.CONTENT_LIKE), anyLong());
    }

    private void pending(Set<Long> ids) {
        when(target.type()).thenReturn(CounterType.CONTENT_LIKE);
        when(buffer.scanIds(CounterKeys.inflightPrefix(CounterType.CONTENT_LIKE))).thenReturn(Set.of());
        when(buffer.scanIds(CounterKeys.pendingPrefix(CounterType.CONTENT_LIKE))).thenReturn(ids);
    }

    private static ObjectProvider<CounterTarget> provider(CounterTarget... targets) {
        return new ObjectProvider<>() {
            @Override
            public Stream<CounterTarget> stream() {
                return Stream.of(targets);
            }
        };
    }
}

package cn.diaojiahong.myyoutube.counter;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.counter.CounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(CounterRepairTest.Config.class)
class CounterRepairTest {

    @Configuration
    static class Config {
        @Bean
        CounterBuffer counterBuffer() {
            return mock(CounterBuffer.class);
        }

        @Bean
        CounterTarget shareTarget() {
            CounterTarget target = mock(CounterTarget.class);
            when(target.type()).thenReturn(CounterType.CONTENT_SHARE);
            return target;
        }

        @Bean
        CounterFlushService counterFlushService(CounterBuffer buffer, ObjectProvider<CounterTarget> targets) {
            return new CounterFlushService(buffer, targets);
        }
    }

    @Autowired
    private CounterFlushService flush;

    @Autowired
    private CounterBuffer buffer;

    @Autowired
    private CounterTarget shareTarget;

    @BeforeEach
    void isolateMockCalls() {
        reset(buffer, shareTarget);
        when(shareTarget.type()).thenReturn(CounterType.CONTENT_SHARE);
    }

    @Test
    void repairDiscardsPendingThenOverwritesWithAuthoritativeValue() {
        when(shareTarget.authoritativeValue(4L)).thenReturn(3L);

        int repaired = flush.repair(CounterType.CONTENT_SHARE, List.of(4L));

        assertEquals(1, repaired);
        var order = inOrder(buffer, shareTarget);
        order.verify(buffer).discard(CounterType.CONTENT_SHARE, 4L);
        order.verify(shareTarget).authoritativeValue(4L);
        order.verify(shareTarget).overwrite(4L, 3L);
        order.verify(buffer).clearDirty(CounterType.CONTENT_SHARE, 4L);
    }

    @Test
    void duplicateIdsAreCoveredOnce() {
        when(shareTarget.authoritativeValue(4L)).thenReturn(3L);

        assertEquals(1, flush.repair(CounterType.CONTENT_SHARE, List.of(4L, 4L)));

        verify(shareTarget).overwrite(4L, 3L);
    }

    @Test
    void missingAuthorityClearsDirtyWithoutOverwrite() {
        when(shareTarget.authoritativeValue(4L)).thenReturn(null);

        assertEquals(0, flush.repair(CounterType.CONTENT_SHARE, List.of(4L)));

        verify(shareTarget, never()).overwrite(4L, 0L);
        verify(buffer).clearDirty(CounterType.CONTENT_SHARE, 4L);
    }

    @Test
    void overwriteFailureLeavesDirtyForTheNextRound() {
        when(shareTarget.authoritativeValue(4L)).thenReturn(3L);
        doThrow(new IllegalStateException("db down")).when(shareTarget).overwrite(4L, 3L);

        assertThrows(IllegalStateException.class, () -> flush.repair(CounterType.CONTENT_SHARE, List.of(4L)));

        verify(buffer, never()).clearDirty(CounterType.CONTENT_SHARE, 4L);
    }

    @Test
    void rejectsEmptyIdsAndTypesThisServiceDoesNotOwn() {
        assertThrows(BizException.class, () -> flush.repair(CounterType.CONTENT_SHARE, List.of()));
        assertThrows(BizException.class, () -> flush.repair(CounterType.CONTENT_SHARE, List.of(0L)));
        List<Long> tooMany = new ArrayList<>();
        for (long id = 1; id <= PageQuery.MAX_PAGE_SIZE + 1; id++) {
            tooMany.add(id);
        }
        assertThrows(BizException.class, () -> flush.repair(CounterType.CONTENT_SHARE, tooMany));
        assertThrows(BizException.class, () -> flush.repair(CounterType.USER_FANS, List.of(1L)));
    }
}

package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotSearchDailyArchiveTest {
    final LocalDate day = LocalDate.of(2026, 10, 20);
    final HotSearchStore store = mock(HotSearchStore.class);
    final HotSearchService service = mock(HotSearchService.class);
    final HotSearchHistoryStore history = mock(HotSearchHistoryStore.class);
    final HotSearchProperties properties = new HotSearchProperties();
    final IdempotentGuard guard = mock(IdempotentGuard.class);
    final IdempotentGuard.Lease lease = mock(IdempotentGuard.Lease.class);

    @BeforeEach void setup() {
        when(guard.acquire("{hot-search}:refresh", IdempotentGuard.DEFAULT_LEASE_SECONDS)).thenReturn(lease);
        when(lease.acquired()).thenReturn(true);
        when(store.closedThrough()).thenReturn(day.minusDays(1));
        when(store.closeDay(any())).thenReturn(true);
    }

    HotSearchDailyArchive job(int hour) {
        var at = day.plusDays(1).atTime(hour, 0).atZone(HotSearchStore.STAT_ZONE).toInstant();
        return new HotSearchDailyArchive(store, service, history, properties, guard, Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test void atFourRunsOneFinalPassThenClosesAndBuildsTheDailySummary() {
        when(store.nextSummaryDate()).thenReturn(day);

        job(4).run();

        var order = inOrder(service, store, history);
        order.verify(service).finishDay(eq(day), eq(lease), anyLong());
        order.verify(store).closeDay(day);
        order.verify(history).rebuildDay(day);
        order.verify(store).summaryCompleted(day);
    }

    @Test void beforeFourDoesNotCloseYesterdayButCanRetryAnOlderSummary() {
        when(store.nextSummaryDate()).thenReturn(day.minusDays(1));

        job(3).run();

        verifyNoInteractions(service);
        verify(store, never()).closeDay(any());
        verify(history).rebuildDay(day.minusDays(1));
    }

    @Test void failedGroupingStillAdvancesTheDateUnderAnOwnedLease() {
        doThrow(new IllegalStateException("model unavailable")).when(service).finishDay(any(), any(), anyLong());

        job(4).run();

        verify(lease).assertOwned();
        verify(store).closeDay(day);
    }

    @Test void lostLeaseDoesNotAdvanceOrBuildASummary() {
        doThrow(new IllegalStateException("lease lost")).when(lease).assertOwned();

        job(4).run();

        verify(store, never()).closeDay(any());
        verifyNoInteractions(history);
    }

    @Test void aBusyRefreshDefersClosingToAnotherRun() {
        when(guard.acquire(anyString(), anyLong())).thenThrow(new IllegalStateException("busy"));

        job(4).run();

        verifyNoInteractions(service, store, history);
    }

    @Test void failedSummaryIsRetriedWithoutAnotherFinalPassOrDateShift() {
        var closed = new AtomicReference<>(day.minusDays(1));
        when(store.closedThrough()).thenAnswer(call -> closed.get());
        when(store.closeDay(day)).thenAnswer(call -> { closed.set(day); return true; });
        when(store.nextSummaryDate()).thenReturn(day);
        doThrow(new IllegalStateException("database unavailable")).doNothing().when(history).rebuildDay(day);
        var job = job(4);

        job.run();
        verify(store, never()).summaryCompleted(any());
        job.run();

        verify(service, times(1)).finishDay(eq(day), eq(lease), anyLong());
        verify(store, times(1)).closeDay(day);
        verify(history, times(2)).rebuildDay(day);
        verify(store).summaryCompleted(day);
    }

    @Test void recoveryClosesMissedDatesOneAtATimeInsteadOfSkippingToYesterday() {
        var closed = new AtomicReference<>(day.minusDays(3));
        when(store.closedThrough()).thenAnswer(call -> closed.get());
        when(store.closeDay(any())).thenAnswer(call -> { closed.set(call.getArgument(0)); return true; });
        var job = job(12);

        job.run();
        job.run();
        job.run();

        var order = inOrder(service);
        order.verify(service).finishDay(eq(day.minusDays(2)), eq(lease), anyLong());
        order.verify(service).finishDay(eq(day.minusDays(1)), eq(lease), anyLong());
        order.verify(service).finishDay(eq(day), eq(lease), anyLong());
    }

    @Test void initializesOnlyWhenTheCursorIsMissingAndStartsFromTheLatestDueDate() {
        when(store.closedThrough()).thenReturn(null);
        when(store.initializeArchive(day.minusDays(1))).thenReturn(day.minusDays(1));

        job(4).run();

        verify(store).initializeArchive(day.minusDays(1));
        verify(service).finishDay(eq(day), eq(lease), anyLong());
    }

    @Test void disabledHotSearchDoesNotRunTheArchive() {
        properties.setEnabled(false);

        job(4).run();

        verifyNoInteractions(guard, service, store, history);
    }
}

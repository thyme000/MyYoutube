package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@RequiredArgsConstructor
public class HotSearchDailyArchive {
    private final HotSearchStore store;
    private final HotSearchService service;
    private final HotSearchHistoryStore history;
    private final HotSearchProperties properties;
    private final IdempotentGuard guard;
    private final Clock clock;
    private final HotSearchMetrics metrics;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Shanghai")
    @Scheduled(fixedDelayString = "${my-youtube.search.hot.refresh-millis:300000}",
            initialDelayString = "${my-youtube.search.hot.refresh-millis:300000}")
    public void run() {
        if (!properties.isEnabled()) return;
        try (var lease = guard.acquire("{hot-search}:refresh", IdempotentGuard.DEFAULT_LEASE_SECONDS)) {
            if (!lease.acquired()) return;
            var now = clock.instant().atZone(HotSearchStore.STAT_ZONE);
            LocalDate dueThrough = now.toLocalDate().minusDays(now.getHour() >= 4 ? 1 : 2);
            LocalDate closedThrough = store.closedThrough();
            if (closedThrough == null) closedThrough = store.initializeArchive(dueThrough.minusDays(1));
            if (closedThrough.isBefore(dueThrough)) closeNextDay(closedThrough.plusDays(1), lease);
            rebuildNextDay(lease);
        } catch (RuntimeException error) {
            log.warn("Hot search daily archive deferred errorType={}", error.getClass().getSimpleName());
        }
    }

    private void closeNextDay(LocalDate date, IdempotentGuard.Lease lease) {
        long started = System.nanoTime();
        log.info("Hot search daily closing started date={}", date);
        try {
            service.finishDay(date, lease, started + TimeUnit.SECONDS.toNanos(properties.getRoundBudgetSeconds()));
        } catch (RuntimeException error) {
            log.warn("Hot search daily final pass incomplete date={} errorType={}", date, error.getClass().getSimpleName());
        }
        // 模型或写库失败仍顺延；租约失效则不能推进日期。
        lease.assertOwned();
        boolean advanced = metrics.call(HotSearchMetrics.Operation.CLOSE_DAY, () -> store.closeDay(date));
        log.info("Hot search daily closing completed date={} advanced={} carryTo={} elapsedMs={}",
                date, advanced, date.plusDays(1), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private void rebuildNextDay(IdempotentGuard.Lease lease) {
        LocalDate date = store.nextSummaryDate();
        if (date == null) return;
        try {
            lease.assertOwned();
            metrics.run(HotSearchMetrics.Operation.SUMMARY, () -> history.rebuildDay(date));
            lease.assertOwned();
            metrics.run(HotSearchMetrics.Operation.SUMMARY_ACK, () -> store.summaryCompleted(date));
        } catch (RuntimeException error) {
            log.warn("Hot search daily summary deferred date={} errorType={}", date, error.getClass().getSimpleName());
        }
    }
}

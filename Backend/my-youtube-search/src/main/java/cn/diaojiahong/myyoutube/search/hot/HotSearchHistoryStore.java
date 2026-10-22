package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.search.mapper.HotSearchHistoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Repository
public class HotSearchHistoryStore {
    public record GroupedEvent(String eventId, LocalDate statDate, long happenedAt, long userId,
                               String rawKeyword, String groupingKeyword, String groupId,
                               String groupName, String vectorVersion) {
        public long eventHour() {
            return Math.floorDiv(happenedAt, Duration.ofHours(1).toMillis());
        }
    }

    private final HotSearchHistoryMapper mapper;
    private final TransactionTemplate transaction;

    public HotSearchHistoryStore(HotSearchHistoryMapper mapper, TransactionTemplate transaction) {
        this.mapper = mapper;
        this.transaction = transaction;
    }

    public void append(List<GroupedEvent> events) {
        if (events.isEmpty()) return;
        long started = System.nanoTime();
        try {
            transaction.executeWithoutResult(status -> mapper.append(events));
            log.info("Hot search history append completed batchSize={} elapsedMs={}", events.size(), elapsedMillis(started));
        } catch (RuntimeException error) {
            log.warn("Hot search history append failed batchSize={} elapsedMs={} errorType={}",
                    events.size(), elapsedMillis(started), error.getClass().getSimpleName());
            throw error;
        }
    }

    /**
     * 按归档日期、组重算搜索次数、去重用户数和热度（用户 + 原始小时去重），并按热度重排。
     * 事务内替换当日汇总，重试不累加，失败保留旧结果。
     */
    public void rebuildDay(LocalDate date) {
        long started = System.nanoTime();
        log.info("Hot search history rebuild started date={}", date);
        try {
            Integer groups = transaction.execute(status -> {
                mapper.deleteDay(date);
                return mapper.rebuildDay(date);
            });
            log.info("Hot search history rebuild completed date={} groups={} elapsedMs={}", date, groups, elapsedMillis(started));
        } catch (RuntimeException error) {
            log.warn("Hot search history rebuild failed date={} elapsedMs={} errorType={}",
                    date, elapsedMillis(started), error.getClass().getSimpleName());
            throw error;
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}

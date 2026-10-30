package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class HotSearchMetrics {
    public enum Operation { REFRESH, FINAL_PASS, MODEL_INFO, EMBEDDING, GROUPING, CONTRIBUTION,
        HISTORY_WRITE, HISTORY_ACK, CLOSE_DAY, SUMMARY, SUMMARY_ACK, PUBLISH }
    public enum RecordResult {
        ACCEPTED, FILTERED, CAPACITY, REJECTED, FAILURE
    }
    public enum EventResult {
        COMMITTED, ACKNOWLEDGED, DEFERRED, EXCLUDED
    }
    public enum ReadKind {
        HOT, NAMED
    }
    public enum ReadResult {
        HIT, MISS, TIMEOUT, REJECTED, FAILURE, INTERRUPTED, BUDGET
    }
    private final MeterRegistry registry;

    private final EnumMap<Operation, Timer[]> timers = new EnumMap<>(Operation.class);
    private volatile double lastPublish = Double.NaN;
    private volatile double displayed = Double.NaN;
    private volatile double activeGroups = Double.NaN;

    public HotSearchMetrics(MeterRegistry registry) {
        this.registry = registry;
        quietly(() -> {
            for (HotSearchMetrics.Operation operation : Operation.values()) {
                Timer[] results = new Timer[2];
                for (int i = 0; i < results.length; i++) {
                    results[i] = Timer.builder(MetricNames.HOT_SEARCH_DURATION)
                            .tags("operation", label(operation), "outcome", i == 0 ? "success" : "failure")
                            .serviceLevelObjectives(
                                    Duration.ofMillis(50),
                                    Duration.ofSeconds(1),
                                    Duration.ofSeconds(10),
                                    Duration.ofSeconds(30), Duration.ofMinutes(2)).register(registry);
                }
                timers.put(operation, results);
            }
            for (HotSearchMetrics.RecordResult result : RecordResult.values()) record(result, 0);
            for (HotSearchMetrics.EventResult result : EventResult.values()) events(result, 0);
            for (HotSearchMetrics.ReadKind kind : ReadKind.values()) for (HotSearchMetrics.ReadResult result : ReadResult.values()) read(kind, result, 0);
            for (HotSearchMetrics.Operation operation : new Operation[]{Operation.EMBEDDING, Operation.GROUPING}) modelItems(operation, 0, 0);
            Gauge.builder(MetricNames.HOT_SEARCH_LAST_PUBLISH_SECONDS, this, value -> value.lastPublish)
                    .register(registry);
            Gauge.builder(MetricNames.HOT_SEARCH_DISPLAYED, this, value -> value.displayed).register(registry);
            Gauge.builder(MetricNames.HOT_SEARCH_ACTIVE_GROUPS, this, value -> value.activeGroups).register(registry);
        });
    }

    public <T> T call(Operation operation, Supplier<T> action) {
        long started = System.nanoTime();
        boolean success = false;
        try {
            T value = action.get();
            success = true;
            return value;
        } finally {
            completed(operation, started, success);
        }
    }

    public void run(Operation operation, Runnable action) {
        call(operation, () -> {
            action.run();
            return null;
        });
    }

    public void completed(Operation operation, long started, boolean success) {
        quietly(() -> {
            Timer[] results = timers.get(operation);
            if (results != null) results[success ? 0 : 1].record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        });
    }

    public void record(RecordResult result) {
        record(result, 1);
    }

    private void record(RecordResult result, int count) {
        quietly(() -> registry.counter(MetricNames.HOT_SEARCH_RECORDS, "outcome", label(result)).increment(count));
    }

    public void events(EventResult result, int count) {
        quietly(() -> registry.counter(MetricNames.HOT_SEARCH_HISTORY_EVENTS, "outcome", label(result))
                .increment(count));
    }

    public void read(ReadKind kind, ReadResult result) {
        read(kind, result, 1);
    }

    private void read(ReadKind kind, ReadResult result, int count) {
        quietly(() -> registry.counter(
                MetricNames.HOT_SEARCH_CACHE_READS,
                "kind",
                label(kind),
                "outcome",
                label(result))
                .increment(count));
    }

    public void modelItems(Operation operation, int requested, int accepted) {
        quietly(() -> {
            registry.counter(MetricNames.HOT_SEARCH_MODEL_ITEMS, "operation", label(operation), "outcome", "accepted")
                    .increment(accepted);
            registry.counter(MetricNames.HOT_SEARCH_MODEL_ITEMS, "operation", label(operation), "outcome", "unassigned")
                    .increment(Math.max(0, requested - accepted));
        });
    }

    public void published(int groups, int count, long now) {
        activeGroups = groups;
        displayed = count;
        lastPublish = now / 1000d;
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException unavailable) {
            /* 指标故障不能改变热搜结果或确认顺序。 */
        }
    }
}

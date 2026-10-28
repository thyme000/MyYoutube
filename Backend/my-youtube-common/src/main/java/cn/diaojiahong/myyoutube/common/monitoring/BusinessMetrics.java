package cn.diaojiahong.myyoutube.common.monitoring;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import io.micrometer.core.instrument.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class BusinessMetrics {
    private static final List<String> OUTCOMES = List.of("success", "empty", "no_change", "business_error", "failure");
    private final MeterRegistry registry;
    private final Map<BusinessOperation, Meters> operations = new ConcurrentHashMap<>();
    private record Meters(Timer duration, Map<String, Counter> results, AtomicInteger active) {}

    public BusinessMetrics(MeterRegistry registry) { this.registry = registry; }

    public void registerOperation(BusinessOperation operation) {
        try { operations.computeIfAbsent(operation, this::register); }
        catch (RuntimeException unavailable) { /* Registry failures must not prevent startup. */ }
    }

    @Around("@annotation(observed)")
    public Object observe(ProceedingJoinPoint call, ObservedBusiness observed) throws Throwable {
        Meters meters;
        try { meters = operations.computeIfAbsent(observed.value(), this::register); }
        catch (RuntimeException unavailable) { return call.proceed(); }
        meters.active().incrementAndGet();
        var sample = Timer.start(registry);
        Object result;
        try { result = call.proceed(); }
        catch (Throwable error) {
            finish(meters, sample, error instanceof BizException ? "business_error" : "failure");
            throw error;
        }
        String outcome = outcome(observed.value(), result);
        // Joined transactions may still roll back after the observed method returns.
        if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    finish(meters, sample, status == STATUS_COMMITTED ? outcome : "failure");
                }
            });
        } else finish(meters, sample, outcome);
        return result;
    }

    private Meters register(BusinessOperation operation) {
        var tags = Tags.of("chain", operation.chain(), "operation", operation.name().toLowerCase(Locale.ROOT));
        var counters = new HashMap<String, Counter>();
        for (String outcome : OUTCOMES)
            counters.put(outcome, registry.counter(MetricNames.BUSINESS_OPERATIONS, tags.and("outcome", outcome)));
        var duration = Timer.builder(MetricNames.BUSINESS_DURATION).tags(tags).publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofMinutes(2)).register(registry);
        var active = new AtomicInteger();
        Gauge.builder(MetricNames.BUSINESS_INFLIGHT, active, AtomicInteger::get).tags(tags).register(registry);
        Gauge.builder(MetricNames.BUSINESS_OPERATION_ENABLED, () -> 1).tags(tags).register(registry);
        return new Meters(duration, Map.copyOf(counters), active);
    }

    private String outcome(BusinessOperation operation, Object result) {
        if (Boolean.FALSE.equals(result)) return "no_change";
        if (operation.resultList()) {
            Collection<?> list = result instanceof PageResult<?> page ? page.getList()
                    : result instanceof CursorPage<?, ?> cursor ? cursor.items()
                    : result instanceof Collection<?> values ? values : null;
            if (list != null && list.isEmpty()) return "empty";
        }
        return "success";
    }

    private void finish(Meters meters, Timer.Sample sample, String outcome) {
        meters.active().decrementAndGet();
        try { meters.results().get(outcome).increment(); sample.stop(meters.duration()); }
        catch (RuntimeException unavailable) { /* Observation must not change the business result. */ }
    }
}

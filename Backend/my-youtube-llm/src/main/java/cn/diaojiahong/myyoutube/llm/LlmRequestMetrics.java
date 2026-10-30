package cn.diaojiahong.myyoutube.llm;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Component
public class LlmRequestMetrics {
    private final MeterRegistry registry;

    private final Map<String, AtomicInteger> active = Map.of(
            "chat", new AtomicInteger(), "embeddings", new AtomicInteger(), "query-embedding", new AtomicInteger());

    public LlmRequestMetrics(MeterRegistry registry) {
        this.registry = registry;
        active.forEach((operation, count) -> {
            Gauge.builder(MetricNames.LLM_REQUESTS_ACTIVE, count, AtomicInteger::get)
                    .tag("operation", operation).register(registry);
            for (String outcome : new String[]{"success", "error", "timeout"}) {
                Timer.builder(MetricNames.LLM_REQUEST_DURATION).tags("operation", operation, "outcome", outcome)
                        .register(registry);
            }
        });
    }

    public <T> T record(String operation, Supplier<T> request) {
        AtomicInteger count = active.get(operation);
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        count.incrementAndGet();
        try {
            T result = request.get();
            outcome = "success";
            return result;
        } catch (RuntimeException failure) {
            if (isTimeout(failure)) outcome = "timeout";
            throw failure;
        } finally {
            count.decrementAndGet();
            sample.stop(registry.timer(MetricNames.LLM_REQUEST_DURATION, "operation", operation, "outcome", outcome));
        }
    }

    private boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
            if (cause == cause.getCause()) break;
        }
        return false;
    }
}

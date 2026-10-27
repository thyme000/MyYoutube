package cn.diaojiahong.myyoutube.common.monitoring;

import io.micrometer.core.instrument.*;
import java.time.Clock;

public final class SampleHealth {
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Tags tags;
    private volatile double successful;
    private volatile long lastSuccess;
    public SampleHealth(MeterRegistry metrics, Clock clock, String component, String... labels) {
        this(metrics, clock, component, () -> true, labels);
    }
    public SampleHealth(MeterRegistry metrics, Clock clock, String component, java.util.function.BooleanSupplier enabled, String... labels) {
        this.clock = clock; this.metrics = metrics;
        // Prometheus requires identical label keys for every series in one metric family.
        tags = Tags.of("component", component, "group", "all", "kind", "all").and(labels);
        Gauge.builder(MetricNames.PIPELINE_METRICS_SAMPLE_ENABLED, enabled, value -> value.getAsBoolean() ? 1 : 0).strongReference(true).tags(tags).register(metrics);
        Gauge.builder(MetricNames.PIPELINE_METRICS_SAMPLE_SUCCESS, this, s -> s.successful).tags(tags).register(metrics);
        Gauge.builder(MetricNames.PIPELINE_METRICS_SAMPLE_LAST_SUCCESS_SECONDS, this, s -> s.lastSuccess / 1000d).tags(tags).register(metrics);
        Gauge.builder(MetricNames.PIPELINE_METRICS_SAMPLE_AGE_SECONDS, this, s -> s.lastSuccess == 0 ? Double.NaN
                : Math.max(0, (s.clock.millis() - s.lastSuccess) / 1000d)).tags(tags).register(metrics);
    }
    public void success() { lastSuccess = clock.millis(); successful = 1; }
    public void failure() { successful = 0; metrics.counter(MetricNames.PIPELINE_METRICS_SAMPLE_FAILURES, tags).increment(); }
}

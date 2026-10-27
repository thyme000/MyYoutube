package cn.diaojiahong.myyoutube.observability;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.monitoring.SampleHealth;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.Clock;

public final class FilebeatMetrics {
    private static final Logger log = LoggerFactory.getLogger(FilebeatMetrics.class);
    record Stock(long active, long acked, long ackChangedAt, double deliverySeconds) {}
    private final URI stats, es;
    private final Clock clock;
    private final SampleHealth health;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Stock snapshot;
    public FilebeatMetrics(MonitorProperties monitor, IngestProperties ingest, MeterRegistry metrics, Clock clock) {
        stats = monitor.getFilebeat().resolve("/stats"); es = ingest.getElasticsearch().resolve("/logs-myyoutube-*/_search");
        this.clock = clock; health = new SampleHealth(metrics, clock, "logs");
        Gauge.builder(MetricNames.PIPELINE_LOGS_PENDING, this, m -> m.snapshot == null ? Double.NaN : m.snapshot.active()).register(metrics);
        Gauge.builder(MetricNames.PIPELINE_LOGS_DELIVERY_SECONDS, this, m -> m.snapshot == null ? Double.NaN : m.snapshot.deliverySeconds()).register(metrics);
        Gauge.builder(MetricNames.PIPELINE_LOGS_STALLED_SECONDS, this, m -> m.snapshot == null ? Double.NaN : m.snapshot.active() == 0 ? 0
                : Math.max(0, (clock.millis() - m.snapshot.ackChangedAt()) / 1000d)).register(metrics);
    }
    @Scheduled(fixedDelayString="${my-youtube.monitor.interval-ms:30000}", initialDelayString="${my-youtube.monitor.interval-ms:30000}")
    public void sample() {
        try {
            JsonNode beat = query(stats, null);
            JsonNode active = beat.at("/libbeat/pipeline/events/active"), acked = beat.at("/libbeat/output/events/acked");
            if (!active.isIntegralNumber() || !acked.isIntegralNumber()) throw new IllegalStateException("Incomplete Filebeat stats");
            JsonNode search = query(es, "{\"size\":0,\"query\":{\"range\":{\"event.ingested\":{\"gte\":\"now-1m\"}}},\"aggs\":{\"delay\":{\"max\":{\"field\":\"logDeliverySeconds\"}}}}");
            if (search.path("timed_out").asBoolean() || search.at("/_shards/failed").asInt() > 0
                    || !search.path("aggregations").has("delay")) throw new IllegalStateException("Incomplete log search");
            JsonNode delay = search.at("/aggregations/delay/value");
            long changed = snapshot == null || snapshot.acked() != acked.longValue() || active.longValue() == 0
                    || acked.longValue() < snapshot.acked() ? clock.millis() : snapshot.ackChangedAt();
            snapshot = new Stock(active.longValue(), acked.longValue(), changed, delay.isNumber() ? delay.doubleValue() : Double.NaN);
            health.success();
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            health.failure(); log.warn("Log metrics retain previous sample errorType={}", error.getClass().getSimpleName());
        }
    }
    private JsonNode query(URI uri, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(body == null ? "GET" : "POST", body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Metrics source unavailable");
        return EventBatch.JSON.readTree(response.body());
    }
}

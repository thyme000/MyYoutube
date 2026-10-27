package cn.diaojiahong.myyoutube.observability;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringMetricsTest {
    @Test void defaultQueueTargetsCoverAllPipelineConsumerGroups() {
        var groups = MonitoringConfiguration.defaultGroups("custom-ingest");
        assertEquals(14, groups.size());
        var registry = new SimpleMeterRegistry();
        var sampler = new MqMetrics(groups, (group, topic) -> new MqMetrics.Stock(1, 2), registry, Clock.systemUTC());
        sampler.sample();
        for (String group : groups.keySet()) for (String kind : List.of("normal", "retry"))
            assertEquals(1, registry.get("mq.consumer.pending").tags("group", group, "kind", kind).gauge().value());
        assertEquals(cn.diaojiahong.myyoutube.common.constants.MqConstants.TOPIC_PUBLICATION_EFFECT,
                groups.get("video_publication_notice"));
        assertEquals(cn.diaojiahong.myyoutube.common.constants.MqConstants.TOPIC_PUBLICATION_EFFECT,
                groups.get("video_publication_audio_count"));
        assertEquals(cn.diaojiahong.myyoutube.common.constants.MqConstants.TOPIC_AUDIO_CONTENT_BAN,
                groups.get("video_audio_content_ban"));
    }
    @Test void prometheusExportsFreshnessForBothGlobalAndPerQueueSamplers() {
        var registry = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        var global = new cn.diaojiahong.myyoutube.common.monitoring.SampleHealth(registry, Clock.systemUTC(), "logs");
        var queue = new cn.diaojiahong.myyoutube.common.monitoring.SampleHealth(registry, Clock.systemUTC(), "mq",
                "group", "worker", "kind", "retry");
        global.success(); queue.failure(); global.failure();
        String scrape = registry.scrape();
        for (String metric : List.of("pipeline_metrics_sample_enabled", "pipeline_metrics_sample_success",
                "pipeline_metrics_sample_last_success_seconds", "pipeline_metrics_sample_age_seconds",
                "pipeline_metrics_sample_failures_total")) {
            assertTrue(scrape.lines().anyMatch(line -> line.startsWith(metric + "{") && line.contains("component=\"logs\"")), metric);
            assertTrue(scrape.lines().anyMatch(line -> line.startsWith(metric + "{") && line.contains("group=\"worker\"")
                    && line.contains("kind=\"retry\"")), metric);
        }
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void mqFailurePreservesBacklogAndRestartReadsStockInsteadOfCounter() {
        var registry = new SimpleMeterRegistry(); var fail = new AtomicBoolean(); var pending = new AtomicLong(4);
        MqMetrics.Probe probe = (group, topic) -> {
            if (fail.get() && !topic.startsWith("%RETRY%")) throw new IllegalStateException();
            return new MqMetrics.Stock(topic.startsWith("%RETRY%") ? 0 : pending.get(), 10);
        };
        var sampler = new MqMetrics(Map.of("test-group", "test-topic"), probe, registry, new MutableClock());
        sampler.sample(); assertEquals(4, registry.get("mq.consumer.pending").tag("kind","normal").gauge().value());
        fail.set(true); sampler.sample(); assertEquals(4, registry.get("mq.consumer.pending").tag("kind","normal").gauge().value());
        assertEquals(0, registry.get("pipeline.metrics.sample.success").tag("kind","normal").gauge().value());
        pending.set(0); fail.set(false); sampler.sample(); assertEquals(0, registry.get("mq.consumer.pending").tag("kind","normal").gauge().value());
        var next = new SimpleMeterRegistry(); var restarted = new MqMetrics(Map.of("test-group","test-topic"), probe, next, new MutableClock());
        restarted.sample(); assertEquals(0, next.get("mq.consumer.pending").tag("kind","normal").gauge().value());
        assertNull(next.find("pipeline.metrics.sample.failures").counter());
    }
    @Test void logIdleStallFailureAndCounterResetHaveDistinctMeanings() throws Exception {
        var status = new AtomicInteger(200); var active = new AtomicLong(4); var acked = new AtomicLong(9);
        var requests = new AtomicInteger(); var delay = new AtomicReference<>("13.0");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            String body = exchange.getRequestURI().getPath().equals("/stats")
                    ? "{\"libbeat\":{\"pipeline\":{\"events\":{\"active\":" + active.get() + "}},\"output\":{\"events\":{\"acked\":" + acked.get() + "}}}}"
                    : "{\"_shards\":{\"failed\":0},\"aggregations\":{\"delay\":{\"value\":" + delay.get() + "}}}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(status.get(),bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var monitor = new MonitorProperties(); monitor.setFilebeat(uri); var ingest = new IngestProperties(); ingest.setElasticsearch(uri);
            var clock = new MutableClock(); var registry = new SimpleMeterRegistry();
            var sampler = new FilebeatMetrics(monitor, ingest, registry, clock);
            sampler.sample(); clock.now = clock.now.plusSeconds(120); sampler.sample();
            assertEquals(120, registry.get("pipeline.logs.stalled.seconds").gauge().value());
            assertEquals(13, registry.get("pipeline.logs.delivery.seconds").gauge().value());
            status.set(503); sampler.sample(); assertEquals(4, registry.get("pipeline.logs.pending").gauge().value());
            assertEquals(0, registry.get("pipeline.metrics.sample.success").gauge().value());
            status.set(200); active.set(0); acked.set(1); delay.set("null"); sampler.sample();
            assertEquals(0, registry.get("pipeline.logs.stalled.seconds").gauge().value());
            assertTrue(Double.isNaN(registry.get("pipeline.logs.delivery.seconds").gauge().value()));
            requests.set(0); registry.getMeters().forEach(m -> m.measure().forEach(v -> v.getValue())); assertEquals(0,requests.get());
        } finally { server.stop(0); }
    }
}

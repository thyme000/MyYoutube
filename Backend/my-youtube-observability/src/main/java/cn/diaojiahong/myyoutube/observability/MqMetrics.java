package cn.diaojiahong.myyoutube.observability;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.monitoring.SampleHealth;
import io.micrometer.core.instrument.*;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.impl.MQClientAPIImpl;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper;
import org.apache.rocketmq.remoting.protocol.body.QueueTimeSpan;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.*;
import java.time.Clock;
import java.util.*;

public final class MqMetrics {
    private static final Logger log = LoggerFactory.getLogger(MqMetrics.class);

    record Stock(long pending, double oldestSeconds) {
    }

    @FunctionalInterface interface Probe {
        Stock read(String group, String topic) throws Exception;
    }

    private final Probe probe;
    private final Map<String, String> groups;

    private final Map<String, SampleHealth> health = new LinkedHashMap<>();

    private volatile Map<String, Stock> snapshot = Map.of();

    MqMetrics(Map<String, String> groups, Probe probe, MeterRegistry registry, Clock clock) {
        if (groups.size() > 32 || groups.entrySet().stream().anyMatch(e -> !e.getKey().matches("[A-Za-z0-9_-]{1,128}")
                || !e.getValue().matches("[A-Za-z0-9_-]{1,128}"))) {
            throw new IllegalArgumentException("Invalid monitoring targets");
        }
        this.groups = Map.copyOf(groups);
        this.probe = probe;
        for (String group : groups.keySet()) for (String kind : List.of("normal", "retry")) {
            String key = group + ":" + kind;
            health.put(key, new SampleHealth(registry, clock, "mq", "group", group, "kind", kind));
            Gauge.builder(
                    MetricNames.MQ_CONSUMER_PENDING,
                    this,
                    m -> m.snapshot.containsKey(key) ? m.snapshot.get(key).pending() : Double.NaN)
                    .tags("group", group, "kind", kind).register(registry);
            Gauge.builder(
                    MetricNames.MQ_CONSUMER_OLDEST_SECONDS,
                    this,
                    m -> m.snapshot.containsKey(key) ? m.snapshot.get(key).oldestSeconds() : Double.NaN)
                    .tags("group", group, "kind", kind).register(registry);
        }
    }

    public MqMetrics(MonitorProperties properties, DefaultMQProducer admin, MeterRegistry registry) {
        this(
                properties.getGroups(),
                (group, topic) -> read(admin.getDefaultMQProducerImpl()
                        .getmQClientFactory()
                        .getMQClientAPIImpl(), group, topic),
                registry,
                Clock.systemUTC());
    }

    @Scheduled(
            fixedDelayString="${my-youtube.monitor.interval-ms:30000}",
            initialDelayString="${my-youtube.monitor.interval-ms:30000}")
    public void sample() {
        for (Map.Entry<String,String> target : groups.entrySet()) for (String kind : List.of("normal", "retry")) {
            String key = target.getKey() + ":" + kind;
            try {
                Stock fresh = probe.read(
                        target.getKey(),
                        kind.equals("normal") ? target.getValue() : "%RETRY%" + target.getKey());
                Map<String, Stock> values = new HashMap<>(snapshot);
                values.put(key, fresh);
                snapshot = Map.copyOf(values);
                health.get(key).success();
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                health.get(key).failure();
                log.warn(
                        "MQ metrics retain previous sample group={} kind={} errorType={}",
                        target.getKey(),
                        kind,
                        error.getClass().getSimpleName());
            }
        }
    }

    private static Stock read(MQClientAPIImpl api, String group, String topic) throws Exception {
        org.apache.rocketmq.remoting.protocol.route.TopicRouteData route;
        try {
            route = api.getTopicRouteInfoFromNameServer(topic, 5000L);
        } catch (MQClientException error) {
            if (topic.startsWith("%RETRY%") && missingTopic(error)) return new Stock(0, 0);
            throw error;
        }
        long pending = 0;
        double oldest = 0;
        if (route.getBrokerDatas().isEmpty()) throw new IllegalStateException("Missing broker");
        for (BrokerData broker : route.getBrokerDatas()) {
            String address = broker.getBrokerAddrs().get(0L);
            if (address == null) throw new IllegalStateException("Missing master broker");
            Map<MessageQueue,OffsetWrapper> offsets = api.getConsumeStats(address, group, topic, 5000L)
                    .getOffsetTable();
            if (offsets.isEmpty()) throw new IllegalStateException("Consumer offset unavailable");
            long brokerPending = offsets.values()
                    .stream()
                    .mapToLong(o -> Math.max(0, o.getBrokerOffset() - o.getConsumerOffset()))
                    .sum();
            pending += brokerPending;
            if (brokerPending > 0) {
                List<QueueTimeSpan> spans = api.queryConsumeTimeSpan(address, topic, group, 5000L);
                if (spans.isEmpty()) throw new IllegalStateException("Consumer time span unavailable");
                for (QueueTimeSpan span : spans) {
                    OffsetWrapper offset = offsets.get(span.getMessageQueue());
                    if (offset != null && offset.getBrokerOffset() > offset.getConsumerOffset()) {
                        if (span.getConsumeTimeStamp() <= 0
                                || span.getDelayTime() < 0) {
                            throw new IllegalStateException("Pending timestamp unavailable");
                        }
                        oldest = Math.max(oldest, span.getDelayTime() / 1000d);
                    }
                }
            }
        }
        return new Stock(pending, oldest);
    }

    private static boolean missingTopic(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof MQClientException client
                    && client.getResponseCode() == ResponseCode.TOPIC_NOT_EXIST) {
                return true;
            }
        return false;
    }
}

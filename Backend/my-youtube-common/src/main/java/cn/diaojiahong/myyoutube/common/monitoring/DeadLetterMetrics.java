package cn.diaojiahong.myyoutube.common.monitoring;

import io.micrometer.core.instrument.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ConditionalOnProperty("media.monitor.dlq-groups")
public class DeadLetterMetrics {
    private final Environment environment;
    private final MeterRegistry metrics;

    private final Map<String, AtomicLong> retained = new LinkedHashMap<>();
    private final SampleHealth health;
    private DefaultMQPullConsumer consumer;

    public DeadLetterMetrics(Environment environment, MeterRegistry metrics) {
        this.environment = environment;
        this.metrics = metrics;
        health = new SampleHealth(metrics, java.time.Clock.systemUTC(), "dlq");

        for (String group : environment.getRequiredProperty("media.monitor.dlq-groups").split(",")) {
            var value = new AtomicLong(-1);
            retained.put(group.trim(), value);
            Gauge.builder(MetricNames.MQ_DLQ_RETAINED, value, v -> v.get() < 0 ? Double.NaN : v.get())
                    .tag("group", group.trim()).register(metrics);
        }
    }

    @Scheduled(
            fixedDelayString = "${media.monitor.interval-ms:30000}",
            initialDelayString = "${media.monitor.interval-ms:30000}")
    public synchronized void sample() {
        try {
            if (consumer == null) {
                var candidate = new DefaultMQPullConsumer(
                        environment.getRequiredProperty("spring.application.name") + "_dlq_monitor");
                candidate.setInstanceName(UUID.randomUUID().toString());
                candidate.setVipChannelEnabled(false);
                candidate.setNamesrvAddr(environment.getRequiredProperty("rocketmq.name-server"));
                try {
                    candidate.start();
                    consumer = candidate;
                } catch (Exception error) {
                    candidate.shutdown();
                    throw error;
                }
            }

            Map<String, Long> fresh = new LinkedHashMap<>();
            for (Map.Entry<String, AtomicLong> item : retained.entrySet()) {
                try {
                    long total = 0;
                    for (MessageQueue queue : consumer.fetchSubscribeMessageQueues("%DLQ%" + item.getKey())) {
                        total += Math.max(0, consumer.maxOffset(queue) - consumer.minOffset(queue));
                    }
                    fresh.put(item.getKey(), total);
                    if (total > 0) {
                        log.error(
                                "Dead letters require manual investigation group={} retained={}",
                                item.getKey(),
                                total);
                    }
                } catch (MQClientException absent) {
                    if (isTopicNotExist(absent)) {
                        fresh.put(item.getKey(), 0L);
                    } else {
                        throw absent;
                    }
                }
            }

            fresh.forEach((group, value) -> retained.get(group).set(value));
            health.success();
        } catch (Exception error) {
            health.failure();
            metrics.counter(MetricNames.MQ_DLQ_PROBE_FAILURES).increment();
            log.warn("Dead letter metrics unavailable errorType={}", error.getClass().getSimpleName());
        }
    }

    private static boolean isTopicNotExist(MQClientException error) {
        // RocketMQ wraps route lookup failures in an MQClientException with response code -1.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MQClientException clientError
                    && clientError.getResponseCode() == ResponseCode.TOPIC_NOT_EXIST) {
                return true;
            }
        }
        return false;
    }

    @PreDestroy
    public synchronized void close() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}

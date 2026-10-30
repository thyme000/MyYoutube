package cn.diaojiahong.myyoutube.common.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeadLetterMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final DefaultMQPullConsumer consumer = mock(DefaultMQPullConsumer.class);

    private final DeadLetterMetrics probe = new DeadLetterMetrics(new MockEnvironment()
            .withProperty("media.monitor.dlq-groups", "transcode_draft_worker,transcode_content_worker"), registry);

    DeadLetterMetricsTest() {
        ReflectionTestUtils.setField(probe, "consumer", consumer);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingTopicClearsRetainedAndContinuesSampling(boolean wrapped) throws Exception {
        var queue = new MessageQueue("%DLQ%transcode_draft_worker", "broker", 0);
        var absent = new MQClientException(ResponseCode.TOPIC_NOT_EXIST, "No topic route info in name server");
        MQClientException error = wrapped ? new MQClientException("Can not find Message Queue for this topic", absent) : absent;
        when(consumer.fetchSubscribeMessageQueues(queue.getTopic()))
                .thenReturn(Set.of(queue)).thenThrow(error);
        when(consumer.maxOffset(queue)).thenReturn(8L);
        when(consumer.minOffset(queue)).thenReturn(3L);
        when(consumer.fetchSubscribeMessageQueues("%DLQ%transcode_content_worker")).thenReturn(Set.of());

        probe.sample();
        assertEquals(5, retained("transcode_draft_worker"));
        probe.sample();

        assertEquals(0, retained("transcode_draft_worker"));
        assertNull(registry.find(MetricNames.MQ_DLQ_PROBE_FAILURES).counter());
        verify(consumer, times(2)).fetchSubscribeMessageQueues("%DLQ%transcode_content_worker");
    }

    @ParameterizedTest
    @ValueSource(ints = {ResponseCode.NO_PERMISSION, -1})
    void otherErrorsRemainProbeFailures(int responseCode) throws Exception {
        var queue = new MessageQueue("%DLQ%transcode_draft_worker", "broker", 0);
        var error = new MQClientException("Can not find Message Queue for this topic",
                new MQClientException(responseCode, "Route lookup failed"));
        when(consumer.fetchSubscribeMessageQueues(queue.getTopic()))
                .thenReturn(Set.of(queue)).thenThrow(error);
        when(consumer.maxOffset(queue)).thenReturn(7L);
        when(consumer.minOffset(queue)).thenReturn(2L);
        when(consumer.fetchSubscribeMessageQueues("%DLQ%transcode_content_worker")).thenReturn(Set.of());

        probe.sample();
        probe.sample();

        assertEquals(5, retained("transcode_draft_worker"));
        assertEquals(1, registry.get(MetricNames.MQ_DLQ_PROBE_FAILURES).counter().count());
    }

    @Test
    void newlyCreatedTopicIsSampledOnNextRun() throws Exception {
        var first = new MessageQueue("%DLQ%transcode_draft_worker", "broker", 0);
        var second = new MessageQueue(first.getTopic(), "broker", 1);
        when(consumer.fetchSubscribeMessageQueues(first.getTopic()))
                .thenThrow(new MQClientException("Can not find Message Queue for this topic",
                        new MQClientException(ResponseCode.TOPIC_NOT_EXIST, "No topic route info in name server")))
                .thenReturn(Set.of(first, second));
        when(consumer.maxOffset(first)).thenReturn(10L);
        when(consumer.minOffset(first)).thenReturn(4L);
        when(consumer.maxOffset(second)).thenReturn(8L);
        when(consumer.minOffset(second)).thenReturn(5L);
        when(consumer.fetchSubscribeMessageQueues("%DLQ%transcode_content_worker")).thenReturn(Set.of());

        probe.sample();
        assertEquals(0, retained("transcode_draft_worker"));
        probe.sample();

        assertEquals(9, retained("transcode_draft_worker"));
        assertNull(registry.find(MetricNames.MQ_DLQ_PROBE_FAILURES).counter());
    }

    private double retained(String group) {
        return registry.get(MetricNames.MQ_DLQ_RETAINED).tag("group", group).gauge().value();
    }
}

package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.mq.MqSendUnconfirmedException;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.exception.RemotingConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.messaging.MessagingException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioIndexSyncServiceTest {
    private final AudioMapper rows = mock(AudioMapper.class);
    private final EsSyncPublisher publisher = mock(EsSyncPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong ticker = new AtomicLong();
    private final AudioIndexSyncService service = new AudioIndexSyncService(rows, publisher, registry, ticker::get);

    @BeforeEach void candidates() {
        when(rows.selectList(any())).thenReturn(List.of(audio(1), audio(2)));
        when(rows.update(isNull(), any())).thenReturn(1);
    }

    @Test void badRecordDoesNotBlockNextRecord() {
        doThrow(new IllegalArgumentException("bad document")).when(publisher).syncAudioDoc(1);
        service.deliver();
        verify(publisher).syncAudioDoc(2);
        assertEquals(1, failures("record"));
        assertEquals(0, failures("mq_unavailable"));
    }

    @Test void mqFailureStopsRoundAfterClaimingOnlyOneRecord() {
        doThrow(new MessagingException("send failed", new MQClientException("route unavailable",
                new RemotingConnectException("broker")))).when(publisher).syncAudioDoc(1);
        service.deliver();
        verify(publisher, never()).syncAudioDoc(2);
        verify(rows).update(isNull(), any());
        assertEquals(1, failures("mq_unavailable"));
    }

    @Test void elapsedBudgetStopsAdmissionOfNextRecord() {
        doAnswer(call -> { ticker.set(TimeUnit.SECONDS.toNanos(6)); throw new IllegalArgumentException(); })
                .when(publisher).syncAudioDoc(1);
        service.deliver();
        verify(publisher, never()).syncAudioDoc(2);
        verify(rows).update(isNull(), any());
    }

    @Test void lostClaimSkipsSendWithoutCountingFailure() {
        when(rows.update(isNull(), any())).thenReturn(0, 1);
        service.deliver();
        verify(publisher, never()).syncAudioDoc(1);
        verify(publisher).syncAudioDoc(2);
        assertEquals(0, failures("record"));
    }

    @Test void databaseFailuresAreCountedSeparately() {
        when(rows.selectList(any())).thenThrow(new IllegalStateException("database offline"));
        service.deliver();
        assertEquals(1, failures("scan"));
        doReturn(List.of(audio(1))).when(rows).selectList(any());
        when(rows.update(isNull(), any())).thenThrow(new IllegalStateException("database offline"));
        service.deliver();
        assertEquals(1, failures("claim"));
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest @MethodSource("errors")
    void classificationUsesCauseTypesRatherThanMessages(Throwable error, boolean unavailable) {
        assertEquals(unavailable, AudioIndexSyncService.mqUnavailable(error));
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new IllegalArgumentException("broker down"), false),
                Arguments.of(new MessagingException("send", new IllegalArgumentException()), false),
                Arguments.of(new MessagingException("send", new MQClientException(ResponseCode.MESSAGE_ILLEGAL, "bad message")), false),
                Arguments.of(new MQBrokerException(ResponseCode.MESSAGE_ILLEGAL, "bad message"), false),
                Arguments.of(new MQBrokerException(ResponseCode.SYSTEM_BUSY, "busy"), true),
                Arguments.of(new MQClientException(ResponseCode.TOPIC_NOT_EXIST, "missing topic"), true),
                Arguments.of(new MqSendUnconfirmedException(null), true),
                Arguments.of(new RuntimeException(new java.net.SocketTimeoutException()), true));
    }

    @Test void interruptionRestoresThreadFlag() {
        try {
            assertTrue(AudioIndexSyncService.mqUnavailable(new RuntimeException(new InterruptedException())));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private double failures(String scope) {
        return registry.get(MetricNames.AUDIO_INDEX_DELIVERY_FAILURES).tag("scope", scope).counter().count();
    }
    private static Audio audio(long id) {
        var audio = new Audio(); audio.setId(id); audio.setIndexVersion(1L);
        audio.setIndexAfter(LocalDateTime.now().minusMinutes(1)); return audio;
    }
}

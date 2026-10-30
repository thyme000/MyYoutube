package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import cn.diaojiahong.myyoutube.video.config.AudioCascadeProperties;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioContentBanRecoveryTest {
    final ContentMapper rows = mock(ContentMapper.class);

    final ReviewDecisionService decisions = mock(ReviewDecisionService.class);

    final RedissonClient redisson = mock(RedissonClient.class);

    final RLock lock = mock(RLock.class);

    final AudioCascadeProperties properties = new AudioCascadeProperties();

    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    final AtomicLong clock = new AtomicLong();

    final AudioContentBanRecovery recovery = new AudioContentBanRecovery(
            rows,
            decisions,
            properties,
            redisson,
            metrics,
            clock::get);

    final AudioContentBanMessage first = new AudioContentBanMessage(1L, 20L, 3);

    final AudioContentBanMessage second = new AudioContentBanMessage(2L, 20L, 3);

    AudioContentBanRecoveryTest() {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        properties.setBatchSize(2);
    }

    @Test
    void failedRecordDoesNotBlockLaterRecordsAndIsRetriedOnNextSweep() {
        when(rows.pendingAudioBans(0, 2)).thenReturn(List.of(first, second));
        when(rows.pendingAudioBans(2, 2)).thenReturn(List.of());
        when(decisions.banByAudio(first)).thenThrow(new IllegalStateException("record failure"));
        recovery.recover();
        verify(decisions).banByAudio(second);
        recovery.recover();
        recovery.recover();
        verify(rows, times(2)).pendingAudioBans(0, 2);
        verify(decisions, times(2)).banByAudio(first);
        assertEquals(2, metrics.counter(MetricNames.AUDIO_CASCADE_RECOVERY_FAILED, "scope", "record").count());
        verify(lock, times(3)).unlock();
    }

    @Test
    void timeBudgetStopsStartingNewRecordsAndResumesAfterLastAttempt() {
        when(rows.pendingAudioBans(0, 2)).thenReturn(List.of(first, second));
        when(decisions.banByAudio(first)).thenAnswer(call -> {
            clock.set(6_000_000_000L);
            return true;
        });
        recovery.recover();
        verify(decisions, never()).banByAudio(second);
        when(rows.pendingAudioBans(1, 2)).thenReturn(List.of(second));
        recovery.recover();
        verify(decisions).banByAudio(second);
    }

    @Test
    void anotherInstanceHoldingTheLockSkipsThisRound() {
        when(lock.tryLock()).thenReturn(false);
        recovery.recover();
        verifyNoInteractions(rows, decisions);
        verify(lock, never()).unlock();
    }

    @Test
    void failedScanReleasesLockAndKeepsCursor() {
        when(rows.pendingAudioBans(0, 2)).thenThrow(new IllegalStateException("database unavailable"));
        recovery.recover();
        recovery.recover();
        verify(rows, times(2)).pendingAudioBans(0, 2);
        verify(lock, times(2)).unlock();
        assertEquals(2, metrics.counter(MetricNames.AUDIO_CASCADE_RECOVERY_FAILED, "scope", "scan").count());
    }
}

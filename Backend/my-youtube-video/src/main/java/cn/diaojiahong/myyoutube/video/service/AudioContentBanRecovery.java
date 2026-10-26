package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.video.config.AudioCascadeProperties;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Service
@Slf4j
public class AudioContentBanRecovery {
    private final DynamicMapper dynamics;
    private final ReviewDecisionService decisions;
    private final AudioCascadeProperties properties;
    private final RedissonClient redisson;
    private final MeterRegistry metrics;
    private final LongSupplier ticker;
    // 游标只决定扫描顺序；失败记录绕回后重试，重启后由数据库状态排除已完成项。
    private long cursor;

    @Autowired
    public AudioContentBanRecovery(DynamicMapper dynamics, ReviewDecisionService decisions,
            AudioCascadeProperties properties, RedissonClient redisson, MeterRegistry metrics) {
        this(dynamics, decisions, properties, redisson, metrics, System::nanoTime);
    }
    AudioContentBanRecovery(DynamicMapper dynamics, ReviewDecisionService decisions,
            AudioCascadeProperties properties, RedissonClient redisson, MeterRegistry metrics, LongSupplier ticker) {
        this.dynamics = dynamics; this.decisions = decisions; this.properties = properties;
        this.redisson = redisson; this.metrics = metrics; this.ticker = ticker;
    }

    @Scheduled(fixedDelayString = "#{@audioCascadeProperties.scanIntervalMs}", initialDelayString = "#{@audioCascadeProperties.scanIntervalMs}")
    public void recover() {
        var lock = redisson.getLock("content:audio-ban-recovery");
        if (!lock.tryLock()) return;
        try {
            long deadline = ticker.getAsLong() + TimeUnit.MILLISECONDS.toNanos(properties.getRoundBudgetMs());
            var page = dynamics.pendingAudioBans(cursor, properties.getBatchSize());
            if (page.isEmpty()) { cursor = 0; return; }
            for (var message : page) {
                if (ticker.getAsLong() - deadline >= 0) return;
                try { decisions.banByAudio(message); }
                catch (Exception error) { failed(message.contentId(), "record", error); }
                cursor = message.contentId();
            }
            if (page.size() < properties.getBatchSize()) cursor = 0;
        } catch (Exception error) { failed(null, "scan", error); }
        finally { lock.unlock(); }
    }

    private void failed(Long contentId, String scope, Exception error) {
        metrics.counter("audio.cascade.recovery.failed", "scope", scope).increment();
        log.warn("Audio cascade recovery deferred contentId={} scope={} reason={}", contentId, scope, error.getClass().getSimpleName());
    }
}

package cn.diaojiahong.myyoutube.transcode.schedule;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryItemState;
import cn.diaojiahong.myyoutube.common.monitoring.SampleHealth;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.Batch;
import cn.diaojiahong.myyoutube.transcode.service.RetryBatchStore;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.time.*;

@Component
public class RetryBatchMetrics {
    private static final Logger log=LoggerFactory.getLogger(RetryBatchMetrics.class);
    private final RetryBatchStore batches;
    private final SampleHealth health;
    private final boolean enabled;
    private volatile boolean sampled;
    private volatile Batch current;
    public RetryBatchMetrics(RetryBatchStore batches,MeterRegistry metrics,
            @Value("${my-youtube.observability.monitoring.enabled:false}") boolean enabled) {
        this.batches=batches; this.enabled=enabled;
        health=new SampleHealth(metrics,java.time.Clock.systemUTC(),"retry-batches",() -> enabled);
        Gauge.builder(MetricNames.MEDIA_RETRY_BATCH_ACTIVE,this,m -> m.active()).register(metrics);
        Gauge.builder(MetricNames.MEDIA_RETRY_BATCH_AGE_SECONDS,this,m -> m.age()).register(metrics);
        for (var state : MediaRetryItemState.values())
            Gauge.builder(MetricNames.MEDIA_RETRY_ITEMS,this,m -> m.count(state)).tag("state",state.getCode()).register(metrics);
    }
    private double active() { return !sampled ? Double.NaN : current==null ? 0 : 1; }
    private double age() {
        var snapshot=current;
        return !sampled ? Double.NaN : snapshot==null ? 0
                : Math.max(0,Duration.between(snapshot.createdAt(),LocalDateTime.now(ZoneOffset.UTC)).toSeconds());
    }
    private double count(MediaRetryItemState state) {
        if (!sampled) return Double.NaN;
        var snapshot=current;
        if (snapshot==null) return 0;
        return switch(state) {
            case PENDING -> snapshot.pending(); case RUNNING -> snapshot.running();
            case SUCCESS -> snapshot.succeeded(); case FAILED -> snapshot.failed(); case SKIPPED -> snapshot.skipped();
        };
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void sample() {
        if (!enabled) return;
        try { current=batches.cachedCurrent(); sampled=true; health.success(); }
        catch (RuntimeException error) { health.failure(); log.warn("Retry metrics retain previous sample reason={}",error.toString()); }
    }
}

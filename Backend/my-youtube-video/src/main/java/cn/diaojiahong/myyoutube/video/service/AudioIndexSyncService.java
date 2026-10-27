package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.common.mq.MqSendUnconfirmedException;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Service
@Slf4j
public class AudioIndexSyncService {
    private final AudioMapper rows;
    private final EsSyncPublisher publisher;
    private final MeterRegistry metrics;
    private final LongSupplier ticker;

    @Autowired
    public AudioIndexSyncService(AudioMapper rows, EsSyncPublisher publisher, MeterRegistry metrics) {
        this(rows, publisher, metrics, System::nanoTime);
    }
    AudioIndexSyncService(AudioMapper rows, EsSyncPublisher publisher, MeterRegistry metrics, LongSupplier ticker) {
        this.rows = rows; this.publisher = publisher; this.metrics = metrics; this.ticker = ticker;
        for (String scope : List.of("record", "mq_unavailable", "scan", "claim"))
            metrics.counter(MetricNames.AUDIO_INDEX_DELIVERY_FAILURES, "scope", scope);
    }

    @Scheduled(fixedDelayString = "${my-youtube.audio-index.scan-interval-ms:5000}", initialDelayString = "${my-youtube.audio-index.scan-interval-ms:5000}")
    public void deliver() {
        long deadline = ticker.getAsLong() + TimeUnit.SECONDS.toNanos(5);
        var now = LocalDateTime.now();
        List<Audio> page;
        try {
            page = rows.selectList(new LambdaQueryWrapper<Audio>().apply("index_version > indexed_version")
                    .le(Audio::getIndexAfter, now).orderByAsc(Audio::getIndexAfter).orderByAsc(Audio::getId).last("LIMIT 100"));
        } catch (Exception error) { failed(null, "scan", error); return; }
        for (var a : page) {
            if (ticker.getAsLong() - deadline >= 0) return;
            try {
                if (rows.update(null, new LambdaUpdateWrapper<Audio>().eq(Audio::getId, a.getId()).eq(Audio::getIndexVersion, a.getIndexVersion())
                        .eq(Audio::getIndexAfter, a.getIndexAfter()).set(Audio::getIndexAfter, now.plusSeconds(30))) == 0) continue;
            } catch (Exception error) { failed(a.getId(), "claim", error); return; }
            try { publisher.syncAudioDoc(a.getId()); }
            catch (Exception error) {
                boolean unavailable = mqUnavailable(error);
                failed(a.getId(), unavailable ? "mq_unavailable" : "record", error);
                if (unavailable) return;
            }
        }
    }

    private void failed(Long id, String scope, Exception error) {
        metrics.counter(MetricNames.AUDIO_INDEX_DELIVERY_FAILURES, "scope", scope).increment();
        log.warn("Audio index sync deferred audioId={} scope={} reason={}", id, scope, error.getClass().getSimpleName());
    }

    static boolean mqUnavailable(Throwable error) {
        boolean mqFailure = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) { Thread.currentThread().interrupt(); return true; }
            if (cause instanceof MqSendUnconfirmedException || cause instanceof RemotingException
                    || cause instanceof java.net.SocketException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) return true;
            if (cause instanceof MQClientException client) {
                if (client.getResponseCode() == ResponseCode.MESSAGE_ILLEGAL) return false;
                mqFailure = true;
            }
            if (cause instanceof MQBrokerException broker) {
                if (broker.getResponseCode() == ResponseCode.MESSAGE_ILLEGAL) return false;
                mqFailure = true;
            }
        }
        return mqFailure;
    }
}

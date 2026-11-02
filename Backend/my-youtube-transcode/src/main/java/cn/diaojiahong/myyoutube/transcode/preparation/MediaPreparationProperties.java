package cn.diaojiahong.myyoutube.transcode.preparation;

import java.time.Duration;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "my-youtube.media-preparation")
public class MediaPreparationProperties {
    private java.util.Set<String> videoBrands = java.util.Set.of(
            "isom",
            "iso2",
            "iso3",
            "iso4",
            "iso5",
            "iso6",
            "mp41",
            "mp42",
            "avc1",
            "dash",
            "M4V",
            "M4VH",
            "M4VP");

    private java.util.Set<String> videoProfiles = java.util.Set.of("Baseline", "Constrained Baseline", "Main", "High");
    private int videoMaxLevel = 51;
    private int longEdge = 1600;
    private long outputPixels = 1_600_000;
    private float jpegQuality = 0.85f;
    private long sourceBytes = 32L * 1024 * 1024;
    private long videoSourceBytes = 2L * 1024 * 1024 * 1024;
    private long sourcePixels = 40_000_000;
    private int sourceLongEdge = 16000;
    private Playback playback = new Playback();

    @Value("${my-youtube.media-pipeline.draft-concurrency:1}")
    private int draftConcurrency = 1;

    public int playbackThreads() {
        return playback.resolveThreads(Runtime.getRuntime().availableProcessors(), draftConcurrency);
    }

    @Data
    public static class Playback {
        private Duration startupTimeout = Duration.ofMinutes(2);
        private Duration stallTimeout = Duration.ofMinutes(2);
        /** 0 表示按 JVM 可见 CPU 数和草稿并发自动分配；正数直接使用。 */
        private int threads = 0;
        /** 每秒视频分配的处理秒数；当前各档位独立使用相同系数。 */
        private double durationMultiplier = 3.0;

        public int resolveThreads(int availableProcessors, int draftConcurrency) {
            if (availableProcessors < 1 || draftConcurrency < 1) {
                throw new IllegalArgumentException("CPU 数和草稿并发必须大于 0");
            }
            if (threads > 0) return threads;

            // 多核环境留出一个逻辑 CPU 的额度，剩余额度由本实例的草稿任务平分。
            int encodingProcessors = Math.max(1, availableProcessors - 1);
            return Math.max(1, encodingProcessors / draftConcurrency);
        }

        public Duration totalTimeout(long durationMs) {
            if (durationMs <= 0) throw new IllegalArgumentException("视频时长必须大于 0");
            return startupTimeout.plusMillis((long) Math.ceil(durationMs * durationMultiplier));
        }

        private void validate() {
            if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()
                    || stallTimeout == null || stallTimeout.isZero() || stallTimeout.isNegative()
                    || threads < 0 || !Double.isFinite(durationMultiplier) || durationMultiplier <= 0) {
                throw new IllegalStateException("非法 HLS 执行预算");
            }
        }
    }

    @PostConstruct
    public void validate() {
        if (playback == null) throw new IllegalStateException("缺少 HLS 执行预算");
        playback.validate();

        if (videoSourceBytes < 1
                || videoSourceBytes > 5L * 1024 * 1024 * 1024
                || videoBrands == null
                || videoBrands.isEmpty()
                || videoProfiles == null
                || videoProfiles.isEmpty()
                || videoMaxLevel < 10
                || videoMaxLevel > 62
                || longEdge < 128
                || longEdge > 4096
                || outputPixels < 16384
                || outputPixels > 16_777_216
                || !Float.isFinite(jpegQuality) || jpegQuality <= 0 || jpegQuality > 1
                || sourceBytes < 1 || sourceBytes > 256L * 1024 * 1024
                || sourcePixels < outputPixels || sourcePixels > 100_000_000 || sourceLongEdge < longEdge)
            throw new IllegalStateException("非法媒体准备预算");

        int processors = Runtime.getRuntime().availableProcessors();
        int threads = playback.resolveThreads(processors, draftConcurrency);
        log.info("HLS encoding budget: processors={}, draftConcurrency={}, threads={}, automaticThreads={}, durationMultiplier={}",
                processors, draftConcurrency, threads, playback.getThreads() == 0, playback.getDurationMultiplier());
    }
}

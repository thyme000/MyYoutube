package cn.diaojiahong.myyoutube.common.media;

import com.baomidou.mybatisplus.annotation.EnumValue;

import java.util.List;
import java.util.Objects;

public final class PreparationContracts {
    public static final String PARAMETERS_CHANGED_MESSAGE = "服务端媒体处理配置已更新，请点击重试以重新准备，无需修改或重新上传文件";

    private PreparationContracts() {
    }

    public enum ItemRole {
        VIDEO, AUDIO, IMAGE, FRAME, OCR_IMAGE, HLS_MEDIA, HLS_PLAYLIST;

        @EnumValue
        private final String code = name();

        public String extension() {
            return switch (this) {
                case HLS_MEDIA -> ".mp4";
                case HLS_PLAYLIST -> ".m3u8";
                default -> "";
            };
        }
    }

    /** SOURCE 保留源分辨率并重新编码；其他档位按显示短边定义，仅生成严格更低的档位。 */
    public enum PlaybackQuality {
        P480(480), P720(720), P1080(1080), SOURCE(0);

        private final int shortEdge;

        PlaybackQuality(int shortEdge) { this.shortEdge = shortEdge; }

        public int code() { return shortEdge; }

        public static List<PlaybackQuality> forVideo(int width, int height) {
            int sourceShortEdge = Math.min(width, height);
            return java.util.Arrays.stream(values())
                    .filter(quality -> quality == SOURCE || quality.shortEdge < sourceShortEdge).toList();
        }

        public static PlaybackQuality fromCode(int code) {
            for (PlaybackQuality quality : values()) if (quality.code() == code) return quality;
            return null;
        }
    }

    public record Request(Long preparationId, Long draftId, long mediaVersion, Long userId,
                          ContentMedia media, String coverObjectKey) {
    }

    /** 转码后实际编码信息；平均码率单位为 bit/s，帧率单位为帧/秒。 */
    public record PlaybackMetadata(String codecs, long averageBandwidth, double frameRate) {
        public PlaybackMetadata {
            if (codecs == null || !codecs.matches("avc1\\.[0-9a-fA-F]{6}(,mp4a\\.40\\.2)?")
                    || averageBandwidth <= 0 || !Double.isFinite(frameRate) || frameRate <= 0)
                throw new IllegalArgumentException("Invalid playback metadata");
        }
    }

    /** bandwidth 是 HLS 媒体的峰值码率（bit/s）；非播放素材的 playback 为 null。 */
    public record Item(ItemRole role, int index, String originalObjectKey, String objectKey,
                       long timeMs, int width, int height, long bandwidth, PlaybackMetadata playback) {
        public Item {
            Objects.requireNonNull(role, "Missing prepared item role");
        }
    }

    public record Manifest(Long preparationId, Long draftId, long mediaVersion, Long userId,
                           String parameterVersion,
                           List<Item> items,
                           long durationMs,
                           boolean hasAudio,
                           int videoFrameCount,
                           VideoFrameSampling.Plan videoSampling) {
        public Manifest {
            items = List.copyOf(items);
        }
    }

    public record View(Long preparationId, MediaTaskStatus status, int attempt, int retryCount,
                       String errorMessage, Manifest manifest, boolean parametersChanged) {
    }
}

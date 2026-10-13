package cn.diaojiahong.myyoutube.transcode.preparation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.media-preparation")
public class MediaPreparationProperties {
    private java.util.Set<String> videoBrands = java.util.Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "dash", "M4V", "M4VH", "M4VP");
    private java.util.Set<String> videoProfiles = java.util.Set.of("Baseline", "Constrained Baseline", "Main", "High");
    private int videoMaxLevel = 51;
    private int longEdge = 1600;
    private long outputPixels = 1_600_000;
    private float jpegQuality = 0.85f;
    private long sourceBytes = 32L * 1024 * 1024;
    private long videoSourceBytes = 2L * 1024 * 1024 * 1024;
    private long sourcePixels = 40_000_000;
    private int sourceLongEdge = 16000;

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (videoSourceBytes < 1 || videoSourceBytes > 5L * 1024 * 1024 * 1024 || videoBrands == null || videoBrands.isEmpty() || videoProfiles == null || videoProfiles.isEmpty() || videoMaxLevel < 10 || videoMaxLevel > 62 || longEdge < 128 || longEdge > 4096 || outputPixels < 16384 || outputPixels > 16_777_216
                || !Float.isFinite(jpegQuality) || jpegQuality <= 0 || jpegQuality > 1
                || sourceBytes < 1 || sourceBytes > 256L * 1024 * 1024
                || sourcePixels < outputPixels || sourcePixels > 100_000_000 || sourceLongEdge < longEdge)
            throw new IllegalStateException("非法媒体准备预算");
    }
}

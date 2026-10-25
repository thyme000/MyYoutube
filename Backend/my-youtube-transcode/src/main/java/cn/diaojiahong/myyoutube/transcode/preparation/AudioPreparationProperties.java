package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.media.AudioPreparationContracts;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.audio-preparation")
public class AudioPreparationProperties {
    private long sourceBytes = AudioPreparationContracts.MAX_SOURCE_BYTES;
    private long coverBytes = 8L * 1024 * 1024;
    private int commandTimeoutSeconds = 120;

    @PostConstruct public void validate() {
        if (sourceBytes <= 0 || sourceBytes > AudioPreparationContracts.MAX_SOURCE_BYTES
                || coverBytes <= 0 || coverBytes > 32L * 1024 * 1024 || commandTimeoutSeconds <= 0 || commandTimeoutSeconds > 600)
            throw new IllegalStateException("音频准备预算无效");
    }
}

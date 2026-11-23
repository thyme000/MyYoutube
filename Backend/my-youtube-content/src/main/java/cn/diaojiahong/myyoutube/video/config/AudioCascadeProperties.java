package cn.diaojiahong.myyoutube.video.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.audio-cascade")
public class AudioCascadeProperties {
    private int scanIntervalMs = 300000;
    private int batchSize = 200;
    private int roundBudgetMs = 5000;

    @PostConstruct
    public void validate() {
        if (scanIntervalMs < 1000 || batchSize < 1 || batchSize > 1000 || roundBudgetMs < 1)
            throw new IllegalStateException("音频级联补扫配置无效");
    }
}

package cn.diaojiahong.myyoutube.transcode.task;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.audio-analysis")
public class AudioAnalysisProperties {
    private int minimumCharacters = 8;
    private int chunkCharacters = 12000;
    private String model;
    @PostConstruct public void validate() {
        if (minimumCharacters < 1 || minimumCharacters > 1000 || chunkCharacters < 1024 || chunkCharacters > 30000)
            throw new IllegalStateException("音频初筛配置无效");
    }
}

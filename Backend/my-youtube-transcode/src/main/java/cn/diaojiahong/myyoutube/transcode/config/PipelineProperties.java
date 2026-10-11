package cn.diaojiahong.myyoutube.transcode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.media-pipeline")
public class PipelineProperties {
    private int chunkCharacters = 12000;
    private int leaseSeconds = 150;
    @jakarta.annotation.PostConstruct
    public void validate() {
        if (chunkCharacters < 1024 || chunkCharacters > 30000 || leaseSeconds < 90)
            throw new IllegalStateException("非法加工预算或执行租约");
    }
}

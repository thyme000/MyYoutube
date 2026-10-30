package cn.diaojiahong.myyoutube.transcode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.media-pipeline")
public class PipelineProperties {
    private int retryBatchSize = 20;
    private int retryConcurrency = 1;
    private int retryOccupationSeconds = 3600;
    private int chunkCharacters = 12000;
    private int leaseSeconds = 150;
    private int modelUnavailableWaitSeconds = 600;

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (retryBatchSize < 1
                || retryBatchSize > 100
                || retryConcurrency < 1
                || retryConcurrency > 2
                || retryOccupationSeconds < 1)
            throw new IllegalStateException("非法补跑批次预算");
        if (chunkCharacters < 1024 || chunkCharacters > 30000 || leaseSeconds < 90
                || modelUnavailableWaitSeconds < 30 || modelUnavailableWaitSeconds > 86400)
            throw new IllegalStateException("非法加工预算或执行租约");
    }
}

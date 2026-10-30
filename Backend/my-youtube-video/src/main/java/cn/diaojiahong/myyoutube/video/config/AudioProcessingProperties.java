package cn.diaojiahong.myyoutube.video.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.audio-processing")
public class AudioProcessingProperties {
    private int scanIntervalMs = 2000;
    private int batchSize = 100;
    private int leaseSeconds = 15;
    private int roundBudgetMs = 5000;
    private int connectTimeoutMs = 500;
    private int readTimeoutMs = 1500;
    private int deadlineMinutes = 30;

    @PostConstruct
    public void validate() {
        if (scanIntervalMs < 1 || batchSize < 1 || batchSize > 1000 || leaseSeconds < 1 || deadlineMinutes < 1
                || connectTimeoutMs < 1 || readTimeoutMs < 1 || roundBudgetMs < itemBudgetMs()
                || leaseSeconds * 1000L <= itemBudgetMs() + 1000)
            throw new IllegalStateException("音频处理调度配置无效");
    }

    public long callBudgetMs() {
        return (long) connectTimeoutMs + readTimeoutMs;
    }

    public long itemBudgetMs() {
        return 2 * callBudgetMs();
    }
}

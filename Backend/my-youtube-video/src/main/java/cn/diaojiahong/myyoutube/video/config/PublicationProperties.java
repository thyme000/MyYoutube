package cn.diaojiahong.myyoutube.video.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.publication")
public class PublicationProperties {
    private long scanIntervalMs = 2000;
    private int batchSize = 100;
    private int leaseSeconds = 30;
    private int waitingSeconds = 15;
    private int deadlineMinutes = 30;
    private List<Integer> retryBackoffSeconds = List.of(5, 30, 120);
    // 首次失败之外允许的自动重试次数。
    private int maxAttempts = 3;

    @PostConstruct
    public void validate() {
        if (scanIntervalMs <= 0 || batchSize <= 0 || leaseSeconds <= 0 || waitingSeconds <= 0 || deadlineMinutes <= 0
                || maxAttempts < 0 || retryBackoffSeconds == null || retryBackoffSeconds.size() < maxAttempts
                || retryBackoffSeconds.stream().anyMatch(value -> value == null || value <= 0))
            throw new IllegalStateException("发布调度配置无效");
    }
}

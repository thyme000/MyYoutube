package cn.diaojiahong.myyoutube.video.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.content-resource-cache")
public class ContentResourceCacheProperties {
    // TTL 为上限，实际写入时向下随机偏移，读取不续期。
    private long stateTtlSeconds = 10;
    private long artifactTtlSeconds = 1800;
    private long negativeTtlSeconds = 3;
    private long lockWaitMillis = 2000;

    @PostConstruct
    public void validate() {
        if (stateTtlSeconds < 1 || stateTtlSeconds > 60)
            throw new IllegalStateException("内容状态缓存有效期必须为 1～60 秒");
        if (artifactTtlSeconds < 1 || artifactTtlSeconds > 86400)
            throw new IllegalStateException("内容产物缓存有效期必须为 1～86400 秒");
        if (negativeTtlSeconds < 1 || negativeTtlSeconds > 10)
            throw new IllegalStateException("内容空值缓存有效期必须为 1～10 秒");
        if (lockWaitMillis < 0 || lockWaitMillis > 5000)
            throw new IllegalStateException("内容缓存锁等待时间必须为 0～5000 毫秒");
    }
}

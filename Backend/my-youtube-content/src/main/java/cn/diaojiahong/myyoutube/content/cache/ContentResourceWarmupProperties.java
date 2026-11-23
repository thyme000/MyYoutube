package cn.diaojiahong.myyoutube.content.cache;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.content-resource-cache.warmup")
public class ContentResourceWarmupProperties {
    private boolean enabled = true;
    private long minFans = 10000;

    @PostConstruct
    public void validate() {
        if (minFans < 0) {
            throw new IllegalStateException("内容资源预热的粉丝数门槛不能小于 0");
        }
    }
}

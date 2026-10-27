package cn.diaojiahong.myyoutube.common.concurrent;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.async-timeouts")
public class AsyncTimeoutProperties {
    private Map<String, Policy> resources = new HashMap<>();

    @Data
    public static class Policy {
        private String executor;
        private long timeoutMillis;
    }

    @PostConstruct
    public void validate() {
        resources.forEach((name, policy) -> {
            if (policy == null || policy.getExecutor() == null || policy.getExecutor().isBlank()
                    || policy.getTimeoutMillis() <= 0)
                throw new IllegalStateException("异步超时配置无效: " + name);
        });
    }
}

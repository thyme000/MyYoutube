package cn.diaojiahong.myyoutube.search.recall;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.search.recall")
public class RecallProperties {
    private int waitMillis = 1000;
    private int timeoutMillis = 10000;

    @PostConstruct
    void validate() {
        if (waitMillis <= 0 || timeoutMillis < waitMillis) {
            throw new IllegalStateException("召回等待窗口必须大于 0，硬超时不能小于等待窗口");
        }
    }
}

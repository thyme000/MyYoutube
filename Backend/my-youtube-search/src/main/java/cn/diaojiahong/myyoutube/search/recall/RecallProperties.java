package cn.diaojiahong.myyoutube.search.recall;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.search.recall")
public class RecallProperties {
    private int esTimeoutMillis = 2000;
    private int embeddingTimeoutMillis = 10000;
    private int refillMaxRounds = 2;
    private int refillMaxCandidates = 500;
    private int refillBatchSize = 50;

    @PostConstruct
    void validate() {
        if (esTimeoutMillis <= 0 || embeddingTimeoutMillis <= 0)
            throw new IllegalStateException("ES 和查询向量调用超时必须大于 0");
        if (refillMaxRounds < 0 || refillMaxRounds > 5 || refillMaxCandidates < 1 || refillMaxCandidates > 1000
                || refillBatchSize < 1 || refillBatchSize > refillMaxCandidates)
            throw new IllegalStateException("搜索补取轮次、候选数、批次大小无效");
    }
}

package cn.diaojiahong.myyoutube.search.hot;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.search.hot")
public class HotSearchProperties {
    private boolean enabled = true;
    private String excludedLocation = "hot-search/excluded.txt";
    private double similarityThreshold = 0.82;
    private int minUsers = 3;
    private int maxLength = 64;
    private int batchSize = 200;
    private int maxDistinctPerRound = 32;
    private int maxNewGroupsPerRound = 8;
    private int pendingLimit = 10_000;
    private int partitionCount = 16;
    private int groupLimit = 500;
    private int contributionLimit = 10_000;
    private int aliasTtlSeconds = 86_400;
    private int snapshotTtlSeconds = 900;
    private int modelTimeoutSeconds = 30;
    private int roundBudgetSeconds = 120;

    @PostConstruct
    void validate() {
        if (!Double.isFinite(similarityThreshold) || similarityThreshold <= 0 || similarityThreshold > 1
                || minUsers <= 0 || maxLength <= 0 || batchSize <= 0 || maxDistinctPerRound <= 0
                || maxNewGroupsPerRound <= 0 || pendingLimit <= 0 || partitionCount <= 0 || partitionCount > pendingLimit
                || groupLimit <= 0 || contributionLimit <= 0
                || aliasTtlSeconds <= 0 || snapshotTtlSeconds <= 0 || modelTimeoutSeconds <= 0 || roundBudgetSeconds <= 0) {
            throw new IllegalArgumentException("热搜参数必须为正数，分区数不能超过输入容量，相似度阈值必须在 (0, 1] 内");
        }
    }
}

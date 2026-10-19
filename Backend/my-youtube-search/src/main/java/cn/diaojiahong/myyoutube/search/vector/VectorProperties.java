package cn.diaojiahong.myyoutube.search.vector;

import feign.Request;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Data
@Component
@ConfigurationProperties(prefix = "my-youtube.search.vector")
public class VectorProperties {

    /** 总开关, 关闭即回退纯关键词搜索 */
    private boolean enabled = true;
    /** RRF 平滑常数 */
    private int rrfK = 60;
    /** 双路各取的召回深度 */
    private int recallSize = 50;
    /** Search 等待 LLM 整次请求，包含子批次和内部重试。 */
    private int singleReadTimeoutSeconds = 130;
    private int batchReadTimeoutSeconds = 600;

    public Request.Options embeddingOptions(int inputCount) {
        return new Request.Options(5, TimeUnit.SECONDS,
                inputCount > 1 ? batchReadTimeoutSeconds : singleReadTimeoutSeconds, TimeUnit.SECONDS, true);
    }

    @PostConstruct
    void validate() {
        if (singleReadTimeoutSeconds <= 0 || batchReadTimeoutSeconds <= 0) {
            throw new IllegalStateException("向量 RPC 单条和批量超时必须大于 0");
        }
    }
}

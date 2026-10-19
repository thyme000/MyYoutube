package cn.diaojiahong.myyoutube.llm;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 配置: OpenAI 兼容端点, 厂商不进代码, base-url/model/api-key 全由配置决定
 */
@Data
@ConfigurationProperties(prefix = "my-youtube.llm")
@Component
public class LlmProperties {

    /** 云端厂商(默认 true); 内网自建模型(vLLM/Ollama 等)设 false, 图片走内网 URL 不转 base64 */
    private boolean cloud = true;
    private String baseUrl;
    private String apiKey;
    private String model;
    private double temperature = 0.2;
    private int timeoutSeconds = 20;
    private int maxTokens = 512;
    /** OpenAI 兼容思考强度；none 关闭 Ollama 思考，空值不发送该参数 */
    private String reasoningEffort = "none";

    @PostConstruct
    void validate() {
        require(baseUrl, "my-youtube.llm.base-url");
        require(model, "my-youtube.llm.model");
        require(embedding.baseUrl, "my-youtube.llm.embedding.base-url");
        require(embedding.model, "my-youtube.llm.embedding.model");
        if (embedding.singleTimeoutSeconds <= 0 || embedding.batchTimeoutSeconds <= 0) {
            throw new IllegalStateException("embedding 单条和批量超时必须大于 0");
        }
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少配置 " + key + ", 厂商端点/模型请显式配置");
        }
    }

    /**
     * 向量化配置: 模型/维度/向量版本为一组, 切任何一项即切换向量空间,
     * 所有存量文档向量必须按新版本全量重算(见 search 服务 rebuild 接口)
     */
    @Data
    public static class Embedding {
        /** 内网自建(ollama/vLLM)不填 api-key */
        private String apiKey;
        private String baseUrl;
        private String model;
        /** 向量维度, 必须与 content 索引 dense_vector 映射一致 */
        private int dims = 4096;
        /** 向量空间版本标识; 不填按 model:dims 自动派生 */
        private String version;
        private int batchSize = 16;
        private int singleTimeoutSeconds = 60;
        private int batchTimeoutSeconds = 120;

        public String effectiveVersion() {
            return version != null && !version.isBlank()
                    ? version : model + ":" + dims;
        }
    }

    private Embedding embedding = new Embedding();
}

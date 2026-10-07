package cn.diaojiahong.myyoutube.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 配置: provider 选预置厂商, base-url/model 不填用厂商默认, 填了即覆盖(自定义网关/微调模型)
 */
@Data
@ConfigurationProperties(prefix = "my-youtube.llm")
@Component
public class LlmProperties {

    /** zhipu | deepseek | qwen | openai */
    private String provider = "zhipu";
    /** 云端厂商(默认 true); 内网自建模型(vLLM/Ollama 等)设 false, 图片走内网 URL 不转 base64 */
    private boolean cloud = true;
    private String baseUrl;
    private String apiKey;
    private String model;
    private double temperature = 0.2;
    private int timeoutSeconds = 20;
    private int maxTokens = 512;

    public String effectiveBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank()
                ? baseUrl : LlmProvider.fromCode(provider).getDefaultBaseUrl();
    }

    public String effectiveModel() {
        return model != null && !model.isBlank()
                ? model : LlmProvider.fromCode(provider).getDefaultModel();
    }

    /**
     * 向量化配置: 模型/维度/向量版本为一组, 切任何一项即切换向量空间,
     * 所有存量文档向量必须按新版本全量重算(见 search 服务 rebuild 接口)
     */
    @Data
    public static class Embedding {
        /** 预置厂商, 见 EmbeddingVendor */
        private String provider = "ollama";
        /** 内网自建(ollama/vLLM)不填 api-key */
        private String apiKey;
        private String baseUrl;
        private String model;
        /** 向量维度, 必须与 content 索引 dense_vector 映射一致 */
        private int dims = 4096;
        /** 向量空间版本标识; 不填按 model:dims 自动派生 */
        private String version;
        private int batchSize = 16;
        private int timeoutSeconds = 60;

        public String effectiveBaseUrl() {
            return baseUrl != null && !baseUrl.isBlank()
                    ? baseUrl : EmbeddingVendor.fromCode(provider).getDefaultBaseUrl();
        }

        public String effectiveModel() {
            return model != null && !model.isBlank()
                    ? model : EmbeddingVendor.fromCode(provider).getDefaultModel();
        }

        public String effectiveVersion() {
            return version != null && !version.isBlank()
                    ? version : effectiveModel() + ":" + dims;
        }
    }

    private Embedding embedding = new Embedding();
}

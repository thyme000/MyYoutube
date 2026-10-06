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
}

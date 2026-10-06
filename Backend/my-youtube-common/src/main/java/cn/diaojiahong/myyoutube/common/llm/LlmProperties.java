package cn.diaojiahong.myyoutube.common.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容 LLM 配置: 换厂商只改 base-url/model/api-key 三个值
 */
@Component
@ConfigurationProperties(prefix = "my-youtube.llm")
@Data
public class LlmProperties {

    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
    private String apiKey;
    private String model = "glm-4-flash";
    private int timeoutSeconds = 20;
    private int maxTokens = 512;

}

package cn.diaojiahong.myyoutube.common.llm;

/**
 * 预置厂商: OpenAI 兼容默认端点与默认模型; 显式配置的 base-url/model 优先于这里
 */
public enum LlmProvider {

    ZHIPU("https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    DEEPSEEK("https://api.deepseek.com", "deepseek-chat"),
    QWEN("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash"),
    OPENAI("https://api.openai.com/v1", "gpt-4o-mini");

    private final String defaultBaseUrl;
    private final String defaultModel;

    LlmProvider(String defaultBaseUrl, String defaultModel) {
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public static LlmProvider fromCode(String code) {
        if (code != null) {
            for (LlmProvider p : values()) {
                if (p.name().equalsIgnoreCase(code.trim())) {
                    return p;
                }
            }
        }
        return ZHIPU;
    }
}

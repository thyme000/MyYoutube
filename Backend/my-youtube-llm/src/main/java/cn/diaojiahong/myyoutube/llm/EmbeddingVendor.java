package cn.diaojiahong.myyoutube.llm;

/**
 * 预置 embedding 厂商: OpenAI 兼容默认端点与默认模型; 显式配置的 base-url/model 优先于这里
 */
public enum EmbeddingVendor {

    OLLAMA("http://localhost:11434/v1", "qwen3-embedding:8b"),
    ZHIPU("https://open.bigmodel.cn/api/paas/v4", "embedding-3"),
    /** 阿里百炼 */
    QWEN("https://dashscope.aliyuncs.com/compatible-mode/v1", "text-embedding-v4"),
    SILICONFLOW("https://api.siliconflow.cn/v1", "BAAI/bge-m3"),
    OPENAI("https://api.openai.com/v1", "text-embedding-3-small");

    private final String defaultBaseUrl;
    private final String defaultModel;

    EmbeddingVendor(String defaultBaseUrl, String defaultModel) {
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public static EmbeddingVendor fromCode(String code) {
        if (code != null) {
            for (EmbeddingVendor v : values()) {
                if (v.name().equalsIgnoreCase(code.trim())) {
                    return v;
                }
            }
        }
        return OLLAMA;
    }
}

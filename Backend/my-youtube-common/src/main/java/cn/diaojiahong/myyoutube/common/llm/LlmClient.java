package cn.diaojiahong.myyoutube.common.llm;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 通用 LLM chat 客户端: OpenAI 兼容 /chat/completions, JDK HttpClient 实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final LlmProperties properties;

    public boolean isConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    /**
     * @param messages    角色/内容对, role 为 system/user/assistant
     * @param temperature 采样温度, 抽取类任务建议 0.2
     * @return assistant 回复文本
     */
    public String chat(List<Message> messages, double temperature) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM api-key 未配置");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", properties.getModel());
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", properties.getMaxTokens());
        JsonArray arr = new JsonArray();
        for (Message m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            arr.add(o);
        }
        body.add("messages", arr);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)))
                .build();

        try {
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                String detail = resp.body() == null ? "" : resp.body().substring(0, Math.min(resp.body().length(), 300));
                throw new IllegalStateException("LLM 响应 " + resp.statusCode() + ": " + detail);
            }
            JsonObject root = JsonUtil.GSON.fromJson(resp.body(), JsonObject.class);
            return root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    public record Message(String role, String content) {
    }
}

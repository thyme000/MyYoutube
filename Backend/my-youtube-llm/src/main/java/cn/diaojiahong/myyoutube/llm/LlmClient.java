package cn.diaojiahong.myyoutube.llm;

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
import java.util.Base64;
import java.util.List;

/**
 * 通用 LLM chat 客户端: OpenAI 兼容 /chat/completions, JDK HttpClient 实现;
 * 厂商/模型/温度默认来自 LlmProperties, 调用方可按次覆盖(含 vision 模型与多模态 parts)
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
        return !properties.isCloud() || hasApiKey();
    }

    private boolean hasApiKey() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    /**
     * @param messages    角色/内容对; 纯文本用 content, 多模态用 parts(text/image_url)
     * @param model       可选覆盖, null 用配置值
     * @param temperature 可选覆盖, null 用配置值
     * @param maxTokens   可选覆盖, null 用配置值
     * @return assistant 回复文本
     */
    public String chat(List<Message> messages, String model, Double temperature, Integer maxTokens) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM api-key 未配置");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model != null && !model.isBlank() ? model : properties.getModel());
        body.addProperty("temperature", temperature != null ? temperature : properties.getTemperature());
        body.addProperty("max_tokens", maxTokens != null ? maxTokens : properties.getMaxTokens());
        if (properties.getReasoningEffort() != null && !properties.getReasoningEffort().isBlank()) {
            body.addProperty("reasoning_effort", properties.getReasoningEffort());
        }
        JsonArray arr = new JsonArray();
        for (Message m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            if (m.parts() == null || m.parts().isEmpty()) {
                o.addProperty("content", m.content() == null ? "" : m.content());
            } else {
                JsonArray content = new JsonArray();
                for (Message.Part p : m.parts()) {
                    JsonObject part = new JsonObject();
                    part.addProperty("type", p.type());
                    if ("image_url".equals(p.type())) {
                        JsonObject url = new JsonObject();
                        url.addProperty("url", resolveImageUrl(p.imageDataUrl()));
                        part.add("image_url", url);
                    } else {
                        part.addProperty("text", p.text() == null ? "" : p.text());
                    }
                    content.add(part);
                }
                o.add("content", content);
            }
            arr.add(o);
        }
        body.add("messages", arr);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)));
        if (hasApiKey()) {
            builder.header("Authorization", "Bearer " + properties.getApiKey());
        }
        HttpRequest request = builder.build();

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

    /**
     * 云端厂商拉不到内网 URL: 代拉图片转 base64 data URL;
     * 非云端(内网自建模型)直传 URL, data: 内嵌原样透传
     */
    private String resolveImageUrl(String url) {
        if (url == null || url.isBlank() || url.startsWith("data:") || !properties.isCloud()) {
            return url;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("图片拉取响应 " + resp.statusCode());
            }
            String mime = resp.headers().firstValue("Content-Type")
                    .filter(v -> v.startsWith("image/"))
                    .orElse("image/jpeg");
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(resp.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("图片代拉失败: " + e.getMessage(), e);
        }
    }

    public record Message(String role, String content, List<Part> parts) {

        public static Message text(String role, String content) {
            return new Message(role, content, null);
        }

        /** @param type text | image_url; imageDataUrl 为 http(s) URL 或 data:base64 内嵌 */
        public record Part(String type, String text, String imageDataUrl) {
        }
    }
}

package cn.diaojiahong.myyoutube.llm.embedding;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.llm.LlmProperties;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容 /v1/embeddings 实现: ollama/百炼/硅基流动/OpenAI 等一个协议通吃;
 * 只解析 data[].embedding, 其余厂商特化字段(如重排序)留给未来特化策略
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatEmbeddingProvider implements EmbeddingProvider {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final LlmProperties properties;

    @Override
    public List<float[]> embed(List<String> texts) {
        LlmProperties.Embedding conf = properties.getEmbedding();

        JsonObject body = new JsonObject();
        body.addProperty("model", conf.getModel());
        com.google.gson.JsonArray input = new com.google.gson.JsonArray();
        texts.forEach(input::add);
        body.add("input", input);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(conf.getBaseUrl() + "/embeddings"))
                .timeout(Duration.ofSeconds(conf.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (conf.getApiKey() == null ? "" : conf.getApiKey()))
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)))
                .build();

        try {
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                    throw new ModelUnavailable("LLM", "MODEL_UPSTREAM_UNAVAILABLE", 30, null);
                }
                String detail = resp.body() == null ? "" : resp.body().substring(0, Math.min(resp.body().length(), 300));
                throw new IllegalStateException("Embedding 响应 " + resp.statusCode() + ": " + detail);
            }
            JsonObject root = JsonUtil.GSON.fromJson(resp.body(), JsonObject.class);
            var data = root.getAsJsonArray("data");
            // OpenAI 兼容协议 data[].index 标记输入位置, 按位回填保证与输入顺序一致
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(null);
            }
            boolean[] seen = new boolean[texts.size()];
            for (int i = 0; i < data.size(); i++) {
                JsonObject item = data.get(i).getAsJsonObject();
                int index = item.has("index") ? item.get("index").getAsInt() : i;
                if (index < 0 || index >= texts.size() || seen[index]) {
                    throw new IllegalStateException("Embedding 响应位置重复或越界");
                }
                seen[index] = true;
                try {
                    var arr = item.getAsJsonArray("embedding");
                    float[] vec = new float[arr.size()];
                    for (int j = 0; j < arr.size(); j++) vec[j] = arr.get(j).getAsFloat();
                    vectors.set(index, vec);
                } catch (RuntimeException invalidVector) {
                    // 保留其他输入的有效结果，缺失项交由编排层隔离。
                    log.warn("Embedding 响应向量解析失败 index={}", index);
                }
            }
            return vectors;
        } catch (java.io.IOException e) {
            throw new ModelUnavailable("LLM", "MODEL_CONNECTION_UNAVAILABLE", 30, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailable("LLM", "MODEL_CALL_INTERRUPTED", 30, e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 调用失败: " + e.getMessage(), e);
        }
    }
}

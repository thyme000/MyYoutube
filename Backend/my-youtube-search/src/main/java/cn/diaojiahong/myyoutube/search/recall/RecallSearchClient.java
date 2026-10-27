package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import lombok.RequiredArgsConstructor;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class RecallSearchClient {
    private static final JsonpMapper JSON = new JacksonJsonpMapper();
    private final RestClient restClient;
    private final RecallProperties properties;

    public List<String> search(SearchRequest search) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonElement hit : hits(search)) ids.add(hit.getAsJsonObject().get("_id").getAsString());
        return ids;
    }

    public RecallBatch page(SearchRequest search, int size) throws Exception {
        JsonArray hits = hits(search);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(size, hits.size()); i++) ids.add(hits.get(i).getAsJsonObject().get("_id").getAsString());
        if (hits.size() <= size) return RecallBatch.exhausted(ids);
        JsonArray values = hits.get(size - 1).getAsJsonObject().getAsJsonArray("sort");
        return new RecallBatch(ids, sortValues(values));
    }

    public static List<FieldValue> sortValues(JsonArray values) {
        List<FieldValue> cursor = new ArrayList<>();
        for (JsonElement value : values) {
            if (value.isJsonNull()) cursor.add(FieldValue.NULL);
            else if (value.getAsJsonPrimitive().isNumber()) {
                String number = value.getAsString();
                // 整数保留为 long，避免大 ID 转成 double 后丢失精度，导致分页错位。
                cursor.add(number.contains(".") || number.contains("e") || number.contains("E")
                        ? FieldValue.of(value.getAsDouble()) : FieldValue.of(value.getAsLong()));
            } else cursor.add(FieldValue.of(value.getAsString()));
        }
        return cursor;
    }

    public Map<String, Object> createTimes(List<String> ids) throws Exception {
        SearchRequest query = SearchRequest.of(s -> s.index("content").size(ids.size())
                .query(q -> q.ids(v -> v.values(ids))).source(source -> source.filter(f -> f.includes("createTime"))));
        Map<String, Object> times = new HashMap<>();
        for (JsonElement hit : hits(query)) {
            JsonObject row = hit.getAsJsonObject();
            JsonObject source = row.getAsJsonObject("_source");
            if (source == null || !source.has("createTime") || source.get("createTime").isJsonNull()) continue;
            JsonElement value = source.get("createTime");
            times.put(row.get("_id").getAsString(), value.getAsJsonPrimitive().isNumber() ? value.getAsLong() : value.getAsString());
        }
        return times;
    }

    private JsonArray hits(SearchRequest search) throws Exception {
        var request = new org.elasticsearch.client.Request("POST", "/content/_search");
        var json = new StringWriter();
        try (var generator = JSON.jsonProvider().createGenerator(json)) {
            search.serialize(generator, JSON);
        }
        request.setJsonEntity(json.toString());
        request.addParameter("timeout", properties.getEsTimeoutMillis() + "ms");
        var result = new CompletableFuture<JsonArray>();
        // 单次 ES 调用达到固定超时时，使用底层句柄关闭仍在进行的 HTTP 请求。
        var pending = restClient.performRequestAsync(request, new ResponseListener() {
            @Override public void onSuccess(Response response) {
                try {
                    JsonObject body = JsonUtil.GSON.fromJson(EntityUtils.toString(response.getEntity()), JsonObject.class);
                    if (body.has("timed_out") && body.get("timed_out").getAsBoolean()) throw new TimeoutException("ES 查询超时");
                    if (body.has("_shards") && body.getAsJsonObject("_shards").get("failed").getAsInt() > 0)
                        throw new DependencyException(
                            ErrorCode.DEPENDENCY_FAILURE, "ES 查询分片失败", null);
                    JsonArray hits = body
                            .getAsJsonObject("hits").getAsJsonArray("hits");
                    result.complete(hits);
                } catch (Exception error) { result.completeExceptionally(error); }
            }
            @Override public void onFailure(Exception error) { result.completeExceptionally(error); }
        });
        try {
            return result.get(properties.getEsTimeoutMillis(), TimeUnit.MILLISECONDS);
        } finally {
            if (!result.isDone()) pending.cancel();
        }
    }

}

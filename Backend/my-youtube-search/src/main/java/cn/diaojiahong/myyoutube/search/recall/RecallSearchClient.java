package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class RecallSearchClient {
    private static final JsonpMapper JSON = new JacksonJsonpMapper();
    private final RestClient restClient;

    public List<String> search(SearchRequest search, long deadlineNanos) throws Exception {
        var request = new org.elasticsearch.client.Request("POST", "/content/_search");
        var json = new StringWriter();
        try (var generator = JSON.jsonProvider().createGenerator(json)) {
            search.serialize(generator, JSON);
        }
        request.setJsonEntity(json.toString());
        request.addParameter("timeout", remainingMillis(deadlineNanos) + "ms");
        var result = new CompletableFuture<List<String>>();
        // ES 高层 future 的取消不下传 HTTP，两路共用低层取消句柄。
        var pending = restClient.performRequestAsync(request, new ResponseListener() {
            @Override public void onSuccess(Response response) {
                try {
                    var hits = JsonUtil.GSON.fromJson(EntityUtils.toString(response.getEntity()), JsonObject.class)
                            .getAsJsonObject("hits").getAsJsonArray("hits");
                    List<String> ids = new ArrayList<>(hits.size());
                    hits.forEach(hit -> ids.add(hit.getAsJsonObject().get("_id").getAsString()));
                    result.complete(ids);
                } catch (Exception error) { result.completeExceptionally(error); }
            }
            @Override public void onFailure(Exception error) { result.completeExceptionally(error); }
        });
        try {
            return result.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } finally {
            if (!result.isDone()) pending.cancel();
        }
    }

    public static long remainingMillis(long deadlineNanos) throws InterruptedException, TimeoutException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("召回已取消");
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("召回硬超时");
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }
}

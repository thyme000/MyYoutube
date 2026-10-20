package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.config.RecallConfig;
import cn.diaojiahong.myyoutube.search.vector.QueryVectorRecall;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Timeout(10)
class KeywordRecallHttpTest {
    @Test void keywordKeepsFiltersBoostsSortAndCandidateDepth() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<String>();
        server.createContext("/content/_search", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            QueryVectorHttpTest.send(exchange, "{\"hits\":{\"hits\":[{\"_id\":\"42\"}]}}");
        });
        server.start();
        try (var es = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            var keyword = new KeywordRecallStrategy(new RecallSearchClient(es), new VectorProperties());
            var ctx = SearchContext.of(RecallPipelineTest.context().getQuery(), 7L, List.of(7L));
            ctx.getQuery().setCategory("music");
            assertEquals(List.of("42"), keyword.recall(ctx, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
            var query = JsonUtil.GSON.fromJson(body.get(), JsonObject.class);
            assertEquals(100, query.get("size").getAsInt());
            assertFalse(query.get("_source").getAsBoolean());
            assertEquals("desc", query.getAsJsonArray("sort").get(0).getAsJsonObject().getAsJsonObject("_score").get("order").getAsString());
            assertEquals("desc", query.getAsJsonArray("sort").get(1).getAsJsonObject().getAsJsonObject("createTime").get("order").getAsString());
            var score = query.getAsJsonObject("query").getAsJsonObject("function_score");
            var filters = score.getAsJsonObject("query").getAsJsonObject("bool").getAsJsonArray("must");
            assertTrue(filters.toString().contains("published"));
            assertTrue(filters.toString().contains("public"));
            assertTrue(filters.toString().contains("music"));
            assertTrue(filters.toString().contains("title^5"));
            assertEquals(3, score.getAsJsonArray("functions").size());
            assertEquals(5, score.getAsJsonArray("functions").get(0).getAsJsonObject().get("weight").getAsInt());
            assertTrue(score.toString().contains("authorId"));
            assertTrue(score.toString().contains("playCount"));
            assertTrue(score.toString().contains("likeCount"));
            ctx.getQuery().setSortByLatest(true);
            keyword.recall(ctx, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
            var latest = JsonUtil.GSON.fromJson(body.get(), JsonObject.class);
            assertEquals(2, latest.getAsJsonObject("query").getAsJsonObject("function_score")
                    .getAsJsonArray("functions").size());
        } finally { server.stop(0); }
    }

    @Test void blockedKeywordReturnsVectorAndReleasesSingleEsConnection() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1); var closed = new CountDownLatch(1);
        server.createContext("/content/_search", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); QueryVectorHttpTest.streamUntilCancelled(exchange, closed);
            } else {
                QueryVectorHttpTest.send(exchange, "{\"hits\":{\"hits\":[{\"_id\":\"42\"}]}}");
            }
        });
        server.start();
        var config = new RecallConfig(); var properties = new VectorProperties();
        var recallProperties = new RecallProperties(); recallProperties.setWaitMillis(250);
        var keywordExecutor = config.recallExecutor(); var vectorExecutor = config.queryVectorExecutor(properties);
        try (var es = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort()))
                .setHttpClientConfigCallback(builder -> builder.setMaxConnTotal(1).setMaxConnPerRoute(1)).build()) {
            var keyword = new KeywordRecallStrategy(new RecallSearchClient(es), properties);
            var vectors = mock(QueryVectorRecall.class);
            when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
                assertTrue(entered.await(1, TimeUnit.SECONDS)); return List.of("vector");
            });
            var pipeline = new RecallPipeline(List.of(keyword, new VectorRecallStrategy(vectors, properties)),
                    new RrfFuser(properties), mock(LatestFuser.class), recallProperties, new SimpleMeterRegistry(),
                    Map.of("recallExecutor", keywordExecutor, "queryVectorExecutor", vectorExecutor));
            long start = System.nanoTime();
            assertEquals(List.of("vector"), pipeline.execute(RecallPipelineTest.context()));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed < 1000, "keyword fallback took " + elapsed + "ms");
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            var ctx = RecallPipelineTest.context();
            assertEquals(List.of("42"), keyword.recall(ctx, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
            assertEquals(2, calls.get());
        } finally {
            keywordExecutor.shutdown(); vectorExecutor.shutdown(); server.stop(0); workers.shutdownNow();
        }
    }
}

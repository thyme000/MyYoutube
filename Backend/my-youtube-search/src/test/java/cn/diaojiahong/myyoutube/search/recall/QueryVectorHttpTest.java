package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import cn.diaojiahong.myyoutube.config.RecallConfig;
import cn.diaojiahong.myyoutube.search.vector.QueryVectorRecall;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Retryer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.io.InputStreamReader;
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
class QueryVectorHttpTest {
    @ParameterizedTest @ValueSource(strings = {"rpc", "body", "knn"})
    void blockedHttpReturnsKeywordAndReusesExecutionAndConnectionSlots(String blockedStage) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        var calls = new AtomicInteger(); var knnCalls = new AtomicInteger();
        var stageStarted = new CountDownLatch(1); var peerClosed = new CountDownLatch(1);
        var requestBody = new AtomicReference<String>();
        var timeout = new AtomicReference<String>();
        server.createContext("/query-embedding", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = calls.incrementAndGet();
            if (call == 1 && !blockedStage.equals("knn")) {
                stageStarted.countDown();
                if (blockedStage.equals("rpc")) {
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                } else {
                    streamUntilCancelled(exchange, peerClosed); return;
                }
            }
            send(exchange, JsonUtil.toJson(new LlmPort.EmbeddingsResponse(List.of(java.util.Collections.nCopies(4096, 0.12345678f)), 4096, "test:4096")));
        });
        server.createContext("/content/_search", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            timeout.set(exchange.getRequestURI().getRawQuery());
            int call = knnCalls.incrementAndGet();
            if (blockedStage.equals("knn") && call == 1) {
                stageStarted.countDown(); streamUntilCancelled(exchange, peerClosed); return;
            }
            send(exchange, "{\"hits\":{\"hits\":[{\"_id\":\"vector\"}]}}");
        });
        server.start();
        var properties = new VectorProperties();
        var recallProperties = new RecallProperties(); recallProperties.setWaitMillis(500);
        properties.setQueryMaxConcurrent(1); properties.setQueryQueueCapacity(1);
        var keywordExecutor = new RecallConfig().recallExecutor();
        var vectorExecutor = new RecallConfig().queryVectorExecutor(properties);
        try (var es = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort()))
                .setHttpClientConfigCallback(builder -> builder.setMaxConnTotal(1).setMaxConnPerRoute(1)).build()) {
            var port = port(server.getAddress().getPort());
            var vector = new VectorRecallStrategy(new QueryVectorRecall(port, new RecallSearchClient(es), properties), properties);
            var keyword = mock(KeywordRecallStrategy.class);
            when(keyword.shouldRun(any())).thenReturn(true);
            when(keyword.executorName()).thenReturn("recallExecutor");
            when(keyword.recall(any(), anyLong())).thenAnswer(call -> {
                assertTrue(stageStarted.await(2, TimeUnit.SECONDS)); return List.of("keyword");
            });
            var follows = mock(FollowPort.class); when(follows.getFollowIds(any())).thenReturn(List.of());
            var pipeline = new RecallPipeline(List.of(keyword, vector), new RrfFuser(properties), mock(LatestFuser.class),
                    follows, recallProperties, new SimpleMeterRegistry(), Map.of("recallExecutor", keywordExecutor, "queryVectorExecutor", vectorExecutor));
            long start = System.nanoTime();
            assertEquals(List.of("keyword"), pipeline.execute(RecallPipelineTest.context()));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed < 1000, "keyword response took " + elapsed + "ms");
            System.out.printf("blockedStage=%s budgetMillis=500 keywordElapsedMillis=%d%n", blockedStage, elapsed);
            var idle = vectorExecutor.submit(() -> true);
            assertTrue(idle.get(1, TimeUnit.SECONDS));
            if (!blockedStage.equals("rpc")) assertTrue(peerClosed.await(2, TimeUnit.SECONDS));
            recallProperties.setWaitMillis(1500);
            assertEquals(List.of("keyword", "vector"), pipeline.execute(RecallPipelineTest.context()));
            var body = JsonUtil.GSON.fromJson(requestBody.get(), com.google.gson.JsonObject.class);
            assertTrue(body.has("knn"));
            assertTrue(requestBody.get().contains("published"));
            assertTrue(requestBody.get().contains("public"));
            assertTrue(requestBody.get().contains("test:4096"));
            assertEquals(4096, body.getAsJsonArray("knn").get(0).getAsJsonObject().getAsJsonArray("query_vector").size());
            assertTrue(timeout.get().contains("timeout="));
            assertEquals(2, calls.get());
        } finally {
            keywordExecutor.shutdown(); vectorExecutor.shutdown(); server.stop(0); workers.shutdownNow();
        }
    }

    @Test void interruptCancelsFeignTransportWithoutWaitingForReadTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        var entered = new CountDownLatch(1); var closed = new CountDownLatch(1); var finished = new CountDownLatch(1);
        server.createContext("/query-embedding", exchange -> {
            entered.countDown(); streamUntilCancelled(exchange, closed);
        });
        server.start();
        var caller = Executors.newSingleThreadExecutor();
        try {
            var pending = caller.submit(() -> {
                try { port(server.getAddress().getPort()).embed(new QueryEmbeddingPort.Query("q", System.currentTimeMillis() + 5000),
                        new feign.Request.Options(5000, TimeUnit.MILLISECONDS, 5000, TimeUnit.MILLISECONDS, true)); }
                finally { finished.countDown(); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            pending.cancel(true);
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
        } finally { caller.shutdownNow(); server.stop(0); workers.shutdownNow(); }
    }

    static QueryEmbeddingPort port(int port) {
        return Feign.builder().contract(new SpringMvcContract()).client(new QueryEmbeddingPort.CancellableClient())
                .retryer(Retryer.NEVER_RETRY).encoder((object, type, template) -> template.body(JsonUtil.toJson(object)))
                .decoder((response, type) -> JsonUtil.GSON.fromJson(new InputStreamReader(response.body().asInputStream(), StandardCharsets.UTF_8), type))
                .target(QueryEmbeddingPort.class, "http://127.0.0.1:" + port);
    }

    static void send(HttpExchange exchange, String value) throws java.io.IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        } finally { exchange.close(); }
    }

    static void streamUntilCancelled(HttpExchange exchange, CountDownLatch closed) throws java.io.IOException {
        exchange.sendResponseHeaders(200, 0);
        try {
            while (true) { exchange.getResponseBody().write(' '); exchange.getResponseBody().flush(); Thread.sleep(20); }
        } catch (java.io.IOException error) { closed.countDown(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { exchange.close(); }
    }
}

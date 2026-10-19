package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.RetryableException;
import feign.Retryer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingRpcTimeoutTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void feignHonorsPerRequestTimeoutWithoutSerializingOptions(boolean batch) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        var received = new AtomicReference<String>();
        server.setExecutor(executor);
        server.createContext("/embeddings", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(1600);
                byte[] body = JsonUtil.toJson(new LlmPort.EmbeddingsResponse(List.of(List.of(1f, 2f)), 2, "test:2"))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var port = Feign.builder().contract(new SpringMvcContract()).retryer(Retryer.NEVER_RETRY)
                    .encoder((object, type, template) -> template.body(JsonUtil.toJson(object)))
                    .decoder((response, type) -> JsonUtil.GSON.fromJson(
                            new InputStreamReader(response.body().asInputStream(), StandardCharsets.UTF_8), type))
                    .target(LlmPort.class, "http://127.0.0.1:" + server.getAddress().getPort());
            var properties = new VectorProperties();
            properties.setSingleReadTimeoutSeconds(1);
            properties.setBatchReadTimeoutSeconds(4);
            var texts = batch ? List.of("first", "second") : List.of("single");
            var request = new LlmPort.EmbeddingsRequest(texts);
            if (batch) assertEquals("test:2", port.embeddings(request, properties.embeddingOptions(texts.size())).getVersion());
            else {
                var failure = assertThrows(RetryableException.class, () -> port.embeddings(request, properties.embeddingOptions(1)));
                assertInstanceOf(SocketTimeoutException.class, failure.getCause());
            }
            assertEquals(JsonUtil.toJson(request), received.get());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}

package cn.diaojiahong.myyoutube.llm;

import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.llm.embedding.OpenAiCompatEmbeddingProvider;
import com.sun.net.httpserver.HttpServer;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingTimeoutTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualModelRequestUsesSeparateSingleAndBatchTimeouts(boolean batch) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/embeddings", exchange -> {
            try {
                Thread.sleep(1600);
                byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var properties = new LlmProperties();
            LlmProperties.Embedding conf = properties.getEmbedding();
            conf.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            conf.setModel("timeout-test");
            conf.setSingleTimeoutSeconds(1);
            conf.setBatchTimeoutSeconds(4);
            var provider = new OpenAiCompatEmbeddingProvider(properties);
            if (batch) assertNotNull(provider.embed(List.of("first", "second")).get(0));
            else {
                ModelUnavailable failure = assertThrows(
                        ModelUnavailable.class,
                        () -> provider.embed(List.of("single")));
                assertInstanceOf(HttpTimeoutException.class, failure.getCause());
            }
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}

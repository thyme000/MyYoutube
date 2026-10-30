package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Request;
import feign.Retryer;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HotSearchHttpContractTest {
    @Test
    void metadataUsesGetAndChatOptionsDoNotEnterJsonBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var chatBody = new AtomicReference<String>();
        var infoMethod = new AtomicReference<String>();
        server.createContext("/internal/llm/embedding-info", exchange -> {
            infoMethod.set(exchange.getRequestMethod());
            QueryVectorHttpTest.send(exchange, "{\"version\":\"current\",\"dims\":2}");
        });
        server.createContext("/internal/llm/chat", exchange -> {
            chatBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            QueryVectorHttpTest.send(
                    exchange,
                    "{\"content\":\"{\\\"name\\\":\\\"热搜词\\\"}\",\"finishReason\":\"stop\"}");
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/llm";
            var options = new Request.Options(1, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true);
            Feign.Builder builder = Feign.builder()
                    .contract(new SpringMvcContract())
                    .client(new QueryEmbeddingPort.CancellableClient())
                    .retryer(Retryer.NEVER_RETRY)
                    .encoder((object, type, template) -> template.body(JsonUtil.toJson(object)))
                    .decoder((response, type) -> JsonUtil.GSON.fromJson(
                            new InputStreamReader(response.body().asInputStream(), StandardCharsets.UTF_8),
                            type));
            QueryEmbeddingPort.Info info = builder.target(QueryEmbeddingPort.class, url).info(options);
            assertEquals(new QueryEmbeddingPort.Info("current", 2), info);
            assertEquals("GET", infoMethod.get());
            LlmPort port = builder.target(LlmPort.class, url);
            assertEquals("stop", port.chat(
                            new LlmPort.ChatRequest(List.of(LlmPort.Message.asText("user", "搜索词")), null, 0.0, 128),
                            options)
                    .getFinishReason());
            JsonObject body = JsonParser.parseString(chatBody.get()).getAsJsonObject();
            assertEquals(128, body.get("maxTokens").getAsInt());
            assertTrue(body.has("messages"));
            assertFalse(body.has("options"));
            assertFalse(body.has("readTimeoutMillis"));
        } finally {
            server.stop(0);
        }
    }
}

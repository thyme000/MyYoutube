package cn.diaojiahong.myyoutube.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientTest {
    private HttpServer server;
    private final LlmProperties properties = new LlmProperties();
    private final AtomicReference<JsonObject> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private LlmClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"choices":[{"message":{"content":"完成"},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":3,"total_tokens":23}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setModel("qwen3.5:9b");
        properties.setCloud(false);
        client = new LlmClient(properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void localRequestDisablesThinkingWithoutApiKey() {
        assertTrue(client.isConfigured());
        assertEquals("完成", chat());
        assertEquals("none", requestBody.get().get("reasoning_effort").getAsString());
        assertEquals("qwen3.5:9b", requestBody.get().get("model").getAsString());
        assertNull(authorization.get());
    }

    @Test
    void multimodalRequestUsesConfiguredEffortAndKeepsOverridesAndAuthentication() {
        properties.setCloud(true);
        properties.setApiKey("test-key");
        properties.setReasoningEffort("low");
        String image = "data:image/png;base64,aW1hZ2U=";
        var message = new LlmClient.Message("user", null, List.of(
                new LlmClient.Message.Part("text", "描述图片", null),
                new LlmClient.Message.Part("image_url", null, image)));

        assertEquals("完成", client.chat(List.of(message), "vision-model", 0.1, 1024));
        JsonObject body = requestBody.get();
        assertEquals("low", body.get("reasoning_effort").getAsString());
        assertEquals("vision-model", body.get("model").getAsString());
        assertEquals(0.1, body.get("temperature").getAsDouble());
        assertEquals(1024, body.get("max_tokens").getAsInt());
        assertEquals(image, body.getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonArray("content").get(1).getAsJsonObject()
                .getAsJsonObject("image_url").get("url").getAsString());
        assertEquals("Bearer test-key", authorization.get());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void unsetEffortOmitsParameterForOtherProviders(String effort) {
        properties.setReasoningEffort(effort);
        chat();
        assertFalse(requestBody.get().has("reasoning_effort"));
    }

    @Test
    void cloudStillRequiresApiKey() {
        properties.setCloud(true);
        properties.setApiKey(" ");
        assertFalse(client.isConfigured());
        assertThrows(IllegalStateException.class, this::chat);
        assertNull(requestBody.get());
    }

    @Test
    void structuredRequestKeeps1500BudgetAndReturnsFinishReasonAndUsage() {
        var format = java.util.Map.<String,Object>of("type", "json_schema", "json_schema", java.util.Map.of("name", "summary"));
        var result = client.chatDetailed(List.of(LlmClient.Message.text("user", "资料")), null, 0.1, 1500, format);
        assertEquals(1500, requestBody.get().get("max_tokens").getAsInt());
        assertEquals("json_schema", requestBody.get().getAsJsonObject("response_format").get("type").getAsString());
        assertEquals("stop", result.getFinishReason());
        assertEquals(20, result.getUsage().promptTokens());
        assertEquals(3, result.getUsage().completionTokens());
        assertEquals(23, result.getUsage().totalTokens());
    }

    @Test
    void requestTimeoutRetainsCauseForMonitoring() {
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        properties.setTimeoutSeconds(1);
        var failure = assertThrows(IllegalStateException.class, this::chat);
        assertInstanceOf(HttpTimeoutException.class, failure.getCause());
    }

    private String chat() {
        return client.chat(List.of(LlmClient.Message.text("user", "你好")), null, null, null);
    }
}

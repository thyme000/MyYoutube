package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Request;
import feign.RetryableException;
import feign.Retryer;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class SearchContentSummaryClientTest {
    @Test
    void fixedTimeoutKeepsTheExistingBodyAndDoesNotRetry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var request = new AtomicReference<JsonObject>();
        var delay = new AtomicBoolean();
        var calls = new AtomicInteger();
        server.createContext("/internal/content-summary/list-ordered", exchange -> {
            calls.incrementAndGet();
            request.set(JsonUtil.GSON.fromJson(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    JsonObject.class));
            if (delay.get()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(250);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "[{\"id\":9007199254740993,\"authorId\":7}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            SearchContentSummaryClient client = Feign.builder()
                    .contract(new SpringMvcContract())
                    .retryer(Retryer.NEVER_RETRY)
                    .options(new Request.Options(1000, TimeUnit.MILLISECONDS, 100, TimeUnit.MILLISECONDS, true))
                    .encoder((value, type, template) -> template.body(JsonUtil.toJson(value)))
                    .decoder((response, type) -> JsonUtil.GSON.fromJson(
                            response.body().asReader(StandardCharsets.UTF_8),
                            type))
                    .target(
                            SearchContentSummaryClient.class,
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/content-summary");
            var query = new ContentSummaryPort.SummaryQuery(List.of("9007199254740993"), null);
            List<ContentSummary> result = client.listByIdsOrdered(query);
            assertEquals(9007199254740993L, result.get(0).getId());
            assertEquals("9007199254740993", request.get().getAsJsonArray("ids").get(0).getAsString());
            assertFalse(request.get().has("connectTimeoutMillis"));
            delay.set(true);
            assertThrows(RetryableException.class, () -> client.listByIdsOrdered(query));
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }
}

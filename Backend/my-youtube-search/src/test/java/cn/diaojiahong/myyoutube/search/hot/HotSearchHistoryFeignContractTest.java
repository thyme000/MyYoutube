package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.config.JacksonConfig;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort.*;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Request;
import feign.Retryer;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HotSearchHistoryFeignContractTest {
    @Test void allFiveFeignRoutesRoundTripTheirScopeDatesAndLongIdentifiers() throws Exception {
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        var json = builder.build();
        LocalDate day = LocalDate.of(2026, 10, 20);
        LocalDateTime updated = day.atTime(5, 0);
        long user = 2_000_000_000_000_000_007L;
        long id = 9_007_199_254_740_993L;
        var responses = Map.of(
                "dates", new Page<>(List.of(new Day(day, updated)), null),
                "groups", new Page<>(List.of(new Group("g+special", "主题", "v1", 3, 2, 3, 1, updated)), null),
                "keywords", new Page<>(List.of(new Keyword(id, "  C++ 入门  ", 3, 2)), null),
                "users", new Page<>(List.of(new User(user, 3, 100, 200)), null),
                "events", new Page<>(List.of(new Event(id, "event", day, 100, 0, user, "  C++ 入门  ",
                        "C++ 入门", "g+special", "主题", "v1")), null));
        List<URI> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/hot-search/history", exchange -> {
            requests.add(exchange.getRequestURI());
            String path = exchange.getRequestURI().getPath();
            byte[] body = json.writeValueAsBytes(responses.get(path.substring(path.lastIndexOf('/') + 1)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var port = Feign.builder().contract(new SpringMvcContract()).retryer(Retryer.NEVER_RETRY)
                    .options(new Request.Options(1, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true))
                    .decoder((response, type) -> json.readValue(response.body().asInputStream(), json.getTypeFactory().constructType(type)))
                    .target(HotSearchHistoryPort.class, "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/hot-search/history");
            assertEquals(day, port.dates(null, 20).items().get(0).statDate());
            assertEquals(updated, port.groups(day.toString(), "8", 10).items().get(0).updatedAt());
            assertEquals("  C++ 入门  ", port.keywords(day.toString(), "g+special", null, 20).items().get(0).rawKeyword());
            assertEquals(user, port.users(day.toString(), "g+special", id, null, 20).items().get(0).userId());
            assertEquals(id, port.events(day.toString(), "g+special", id, user, "100.5", 10).items().get(0).id());
            assertEquals(5, requests.size());
            assertEquals(Map.of("date", day.toString(), "cursor", "8", "size", "10"), params(requests.get(1)));
            assertEquals(Map.of("date", day.toString(), "groupId", "g+special", "keywordId", Long.toString(id),
                    "userId", Long.toString(user), "cursor", "100.5", "size", "10"), params(requests.get(4)));
        } finally {
            server.stop(0);
        }
    }

    private Map<String, String> params(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            var parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }
}

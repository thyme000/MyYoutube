package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.search.vector.QueryVectorRecall;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Timeout(10)
class KeywordRecallHttpTest {
    @Test void continuationExcludesFirstPageIdsAndLatestSortUsesOnlyTimeAndId() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonObject> request = new AtomicReference<>();
        server.createContext("/content/_search", exchange -> {
            request.set(JsonUtil.GSON.fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class));
            QueryVectorHttpTest.send(exchange, "{\"hits\":{\"hits\":[]}}");
        });
        server.start();
        try (RestClient es = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            KeywordRecallStrategy keyword = new KeywordRecallStrategy(new RecallSearchClient(es, new RecallProperties()),
                    new VectorProperties(), new RecallRanking(new RecallRankingProperties()));
            SearchContext context = HybridRecallServiceTest.context();
            keyword.load(context, List.of(), 10, Set.of("9007199254740993"));
            assertTrue(request.get().get("query").toString().contains("9007199254740993"));
            assertTrue(request.get().get("query").toString().contains("must_not"));
            assertEquals(3, request.get().getAsJsonArray("sort").size());

            context.getQuery().setSortByLatest(true);
            keyword.load(context, List.of(), 10, Set.of());
            assertEquals(2, request.get().getAsJsonArray("sort").size());
            assertTrue(request.get().getAsJsonArray("sort").get(0).getAsJsonObject().has("createTime"));
            assertTrue(request.get().getAsJsonArray("sort").get(1).getAsJsonObject().has("id"));
        } finally { server.stop(0); }
    }

    @Test void timedOutKeywordHttpCanFinishAfterTheVectorResultIsReturned() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        server.createContext("/content/_search", exchange -> {
            exchange.getRequestBody().readAllBytes();
            entered.countDown();
            try {
                release.await();
                QueryVectorHttpTest.send(exchange, "{\"hits\":{\"hits\":[{\"_id\":\"late-keyword\"}]}}");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally { finished.countDown(); exchange.close(); }
        });
        server.start();
        try (RecallTestSupport runtime = new RecallTestSupport(200);
             RestClient es = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            VectorProperties vectors = new VectorProperties();
            RecallProperties settings = new RecallProperties();
            KeywordRecallStrategy keyword = runtime.proxy(new KeywordRecallStrategy(new RecallSearchClient(es, settings),
                    vectors, new RecallRanking(new RecallRankingProperties())));
            QueryVectorRecall vectorQuery = mock(QueryVectorRecall.class);
            when(vectorQuery.recall(any())).thenReturn(List.of("vector"));
            HybridRecallService hybrid = new HybridRecallService(List.of(keyword, runtime.proxy(new VectorRecallStrategy(vectorQuery, vectors))),
                    new RrfFuser(vectors), settings, runtime.metrics);
            List<String> result = hybrid.recall(HybridRecallServiceTest.context(), 500, ids -> ids).ids();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("vector"), result);
            assertEquals(1, finished.getCount());
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("vector"), result);
        } finally { release.countDown(); server.stop(0); workers.shutdownNow(); }
    }

    @Test void keywordKeepsFiltersBoostsSortAndCandidateDepth() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<String>();
        server.createContext("/content/_search", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            QueryVectorHttpTest.send(exchange, "{\"hits\":{\"hits\":[{\"_id\":\"42\"}]}}");
        });
        server.start();
        try (var es = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            var keyword = new KeywordRecallStrategy(new RecallSearchClient(es, new RecallProperties()), new VectorProperties(), new RecallRanking(new RecallRankingProperties()));
            var ctx = SearchContext.of(HybridRecallServiceTest.context().getQuery(), 7L, List.of(7L));
            ctx.getQuery().setCategory("music");
            assertEquals(List.of("42"), keyword.firstBatch(ctx).join().ids());
            var query = JsonUtil.GSON.fromJson(body.get(), JsonObject.class);
            assertEquals(101, query.get("size").getAsInt());
            assertFalse(query.get("_source").getAsBoolean());
            assertEquals("desc", query.getAsJsonArray("sort").get(0).getAsJsonObject().getAsJsonObject("_score").get("order").getAsString());
            assertEquals("desc", query.getAsJsonArray("sort").get(1).getAsJsonObject().getAsJsonObject("createTime").get("order").getAsString());
            var score = query.getAsJsonObject("query").getAsJsonObject("function_score");
            var filters = score.getAsJsonObject("query").getAsJsonObject("bool").getAsJsonArray("must");
            assertTrue(filters.toString().contains("published"));
            assertTrue(filters.toString().contains("public"));
            assertTrue(filters.toString().contains("music"));
            assertTrue(filters.toString().contains("title^5"));
            assertEquals(4, score.getAsJsonArray("functions").size());
            assertEquals(1.0, score.getAsJsonArray("functions").get(0).getAsJsonObject().get("weight").getAsDouble());
            assertEquals(0.2, score.getAsJsonArray("functions").get(1).getAsJsonObject().get("weight").getAsDouble());
            assertEquals("multiply", score.get("boost_mode").getAsString());
            assertEquals(1.5, score.get("max_boost").getAsDouble());
            assertTrue(score.toString().contains("authorId"));
            assertTrue(score.toString().contains("playCount"));
            assertTrue(score.toString().contains("likeCount"));
            ctx.getQuery().setSortByLatest(true);
            keyword.firstBatch(ctx).join().ids();
            var latest = JsonUtil.GSON.fromJson(body.get(), JsonObject.class);
            assertFalse(latest.getAsJsonObject("query").has("function_score"));
        } finally { server.stop(0); }
    }

    @Test void lookaheadUsesTheLastConsumedSortValuesAndDoesNotSkipTheReservedHit() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonObject> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/content/_search", exchange -> {
            requests.add(JsonUtil.GSON.fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class));
            QueryVectorHttpTest.send(exchange, requests.size() == 1
                    ? "{\"hits\":{\"hits\":[{\"_id\":\"9\",\"sort\":[1.0,100,9]},{\"_id\":\"9007199254740993\",\"sort\":[1.0,100,9007199254740993]},{\"_id\":\"7\",\"sort\":[1.0,100,7]}]}}"
                    : "{\"hits\":{\"hits\":[{\"_id\":\"7\",\"sort\":[1.0,100,7]},{\"_id\":\"6\",\"sort\":[1.0,100,6]}]}}");
        });
        server.start();
        var properties = new VectorProperties(); properties.setRecallSize(1);
        try (RestClient client = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            var keyword = new KeywordRecallStrategy(new RecallSearchClient(client, new RecallProperties()), properties, new RecallRanking(new RecallRankingProperties()));
            SearchContext context = HybridRecallServiceTest.context();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            RecallBatch first = keyword.firstBatch(context).join();
            assertEquals(List.of("9", "9007199254740993"), first.ids()); assertTrue(first.hasMore());
            RecallBatch next = keyword.nextBatch(context, first, 2);
            assertEquals(List.of("7", "6"), next.ids()); assertFalse(next.hasMore());
            assertEquals(9007199254740993L, requests.get(1).getAsJsonArray("search_after").get(2).getAsLong());
            assertEquals(3, requests.get(0).get("size").getAsInt());
            assertFalse(requests.get(0).has("search_after"));
            assertTrue(requests.get(0).getAsJsonArray("sort").get(2).getAsJsonObject().has("id"));
        } finally { server.stop(0); }
    }

    @Test void tagRecallKeepsItsQueryAndBoostsAndNeverEnablesVectorRecall() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<JsonObject>();
        server.createContext("/content/_search", exchange -> {
            body.set(JsonUtil.GSON.fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class));
            QueryVectorHttpTest.send(exchange, "{\"hits\":{\"hits\":[]}}");
        });
        server.start();
        try (RestClient client = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build()) {
            var query = new TagSearchCursorQuery(); query.setTagName("camera");
            SearchContext context = SearchContext.forTag(query, 7L, List.of(9L));
            var properties = new VectorProperties();
            var keyword = new KeywordRecallStrategy(new RecallSearchClient(client, new RecallProperties()), properties, new RecallRanking(new RecallRankingProperties()));
            keyword.firstBatch(context).join();
            JsonObject score = body.get().getAsJsonObject("query").getAsJsonObject("function_score");
            assertEquals("sum", score.get("boost_mode").getAsString());
            assertEquals(5.0, score.getAsJsonArray("functions").get(0).getAsJsonObject().get("weight").getAsDouble());
            assertTrue(score.toString().contains("tagNames^6")); assertTrue(score.toString().contains("60%"));
            assertFalse(new VectorRecallStrategy(mock(QueryVectorRecall.class), properties).shouldRun(context));
        } finally { server.stop(0); }
    }
}

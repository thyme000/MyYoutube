package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.search.vector.QueryVectorRecall;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "recall.refill.integration", matches = "true")
class LiveRecallRefillTest {
    RestClient rest;
    ElasticsearchClient client;
    boolean isolated;
    RecallTestSupport runtime;
    KeywordRecallStrategy keyword;
    HybridRecallService hybrid;

    final QueryVectorRecall vectors = mock(QueryVectorRecall.class);

    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @BeforeEach
    void setup() throws Exception {
        rest = RestClient.builder(HttpHost.create("http://127.0.0.1:19301")).build();
        client = new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
        assertEquals("recall-refill-it", client.info().clusterName());
        isolated = true;
        client.indices().create(i -> i.index("content").settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(m -> m.properties("id", p -> p.long_(v -> v))
                        .properties("title", p -> p.text(v -> v)).properties("tagNames", p -> p.text(v -> v))
                        .properties("status", p -> p.keyword(v -> v)).properties("visibility", p -> p.keyword(v -> v))
                        .properties("playCount", p -> p.long_(v -> v)).properties("likeCount", p -> p.long_(v -> v))
                        .properties("createTime", p -> p.date(v -> v.format("epoch_millis")))));
        var vectorProperties = new VectorProperties();
        vectorProperties.setRecallSize(1);
        var properties = new RecallProperties();
        properties.setRefillBatchSize(2);
        properties.setEsTimeoutMillis(5000);
        runtime = new RecallTestSupport(3000);
        var search = new RecallSearchClient(rest, properties);
        keyword = runtime.proxy(new KeywordRecallStrategy(
                search,
                vectorProperties,
                new RecallRanking(new RecallRankingProperties())));
        when(vectors.recall(any())).thenReturn(List.of());
        hybrid = new HybridRecallService(List.of(keyword, runtime.proxy(new VectorRecallStrategy(vectors, vectorProperties))),
                new RrfFuser(vectorProperties), properties, metrics);
    }

    @AfterEach
    void close() throws Exception {
        if (runtime != null) runtime.close();
        try {
            if (isolated) client.indices().delete(d -> d.index("content"));
        } finally {
            if (rest != null) rest.close();
        }
    }

    void put(long id) throws Exception {
        client.index(i -> i.index("content").id(Long.toString(id)).document(Map.of(
                "id", id, "title", "camera", "status", "published", "visibility", "public",
                "createTime", 1793059200000L, "playCount", 0, "likeCount", 0)));
    }

    SearchContext context() {
        var query = new ContentSearchCursorQuery();
        query.setKeyword("camera");
        return SearchContext.of(query, 7L, List.of());
    }

    @Test
    void filteredCandidatesAreReplacedWithStableTiesAndNoVectorReplay() throws Exception {
        for (long id = 1; id <= 6; id++) put(id);
        client.indices().refresh(r -> r.index("content"));
        SearchContext ctx = context();
        List<List<String>> hydrated = new ArrayList<>();
        List<String> result = hybrid.recall(ctx, 500, ids -> {
            hydrated.add(ids);
            return ids.stream().filter(id -> Long.parseLong(id) <= 2).toList();
        }).ids();
        assertEquals(List.of("2", "1"), result);
        assertEquals(List.of(List.of("6", "5"), List.of("4", "3"), List.of("2", "1")), hydrated);
        verify(vectors, times(1)).recall(any());
        assertEquals(2, metrics.counter(MetricNames.SEARCH_REFILL_ROUNDS).count());
    }

    @Test
    void latestKeywordPagesUseTimeAndIdWithoutHybridFusion() throws Exception {
        for (long id = 1; id <= 6; id++) put(id);
        client.indices().refresh(r -> r.index("content"));
        SearchContext ctx = context();
        ctx.getQuery().setSortByLatest(true);
        RecallBatch first = keyword.load(ctx, List.of(), 2);
        assertEquals(List.of("6", "5"), first.ids());
        assertEquals(2, first.nextCursor().size());
        RecallBatch second = keyword.nextBatch(ctx, first, 2);
        assertEquals(List.of("4", "3"), second.ids());
        verifyNoInteractions(vectors);
    }

    @Test
    void largeLongSortValuesDoNotLoseTheLookaheadHit() throws Exception {
        put(9007199254740994L);
        put(9007199254740993L);
        put(1L);
        client.indices().refresh(r -> r.index("content"));
        SearchContext ctx = context();
        RecallBatch first = keyword.firstBatch(ctx).join();
        assertEquals(List.of("9007199254740994", "9007199254740993"), first.ids());
        assertTrue(first.hasMore());
        assertEquals(9007199254740993L, first.nextCursor().get(2).longValue());
        RecallBatch next = keyword.nextBatch(ctx, first, 2);
        assertEquals(List.of("1"), next.ids());
        assertFalse(next.hasMore());
    }

    @Test
    void anExhaustedSmallResultDoesNotStartRefill() throws Exception {
        put(1L);
        client.indices().refresh(r -> r.index("content"));
        assertTrue(hybrid.recall(context(), 500, ids -> List.of()).ids().isEmpty());
        assertNull(metrics.find(MetricNames.SEARCH_REFILL_ROUNDS).counter());
    }
}

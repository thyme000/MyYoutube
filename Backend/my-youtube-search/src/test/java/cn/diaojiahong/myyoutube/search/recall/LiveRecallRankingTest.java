package cn.diaojiahong.myyoutube.search.recall;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "recall.ranking.integration", matches = "true")
class LiveRecallRankingTest {
    final RecallRankingProperties properties = new RecallRankingProperties();
    final RecallRanking ranking = new RecallRanking(properties);
    final String index = "recall-ranking-it-" + UUID.randomUUID();
    ElasticsearchClient client;

    @BeforeEach void setup() throws Exception {
        var rest = RestClient.builder(HttpHost.create(System.getProperty("recall.ranking.es", "http://127.0.0.1:19301"))).build();
        client = new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
        assertEquals("recall-ranking-it", client.info().clusterName());
        client.indices().create(i -> i.index(index).settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(m -> m.properties("title", p -> p.text(t -> t))
                        .properties("description", p -> p.text(t -> t))
                        .properties("tagNames", p -> p.text(t -> t))
                        .properties("authorName", p -> p.text(t -> t))
                        .properties("authorId", p -> p.long_(l -> l))
                        .properties("playCount", p -> p.long_(l -> l))
                        .properties("likeCount", p -> p.long_(l -> l))
                        .properties("vector", p -> p.denseVector(v -> v.dims(2).index(true).similarity(DenseVectorSimilarity.Cosine)))));
    }

    @AfterEach void close() throws Exception {
        if (client == null) return;
        try { client.indices().delete(d -> d.index(index)); }
        finally { client._transport().close(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothRoutesUseBoundedMultipliersAndMissingCountersPreserveBaseScore(boolean vector) throws Exception {
        put("base", Map.of("authorId", 1, "playCount", 0, "likeCount", 0));
        put("followed", Map.of("authorId", 2, "playCount", 0, "likeCount", 0));
        put("popular", Map.of("playCount", 9999, "likeCount", 999));
        put("saturated", Map.of("playCount", Long.MAX_VALUE, "likeCount", Long.MAX_VALUE));
        put("missing", Map.of());
        var base = vector ? knn(10) : text();
        var ctx = context(List.of(2L));
        var query = vector ? ranking.vector(base, ctx) : ranking.keyword(base, ctx);
        var scores = scores(query);
        double original = scores.get("base");
        var weights = vector ? properties.getVector() : properties.getKeyword();
        assertEquals(1 + weights.getFollow(), scores.get("followed") / original, 0.00001);
        assertEquals(1 + 4 * weights.getPlay() + 3 * weights.getLike(), scores.get("popular") / original, 0.00001);
        assertEquals(weights.getMaxMultiplier(), scores.get("saturated") / original, 0.00001);
        assertEquals(1, scores.get("missing") / original, 0.00001);
    }

    @Test void vectorWeightCanPromoteAFollowedCandidateWithSlightlyLowerSimilarity() throws Exception {
        put("closest", Map.of("authorId", 1));
        put("followed", Map.of("authorId", 2, "vector", List.of(0.98f, 0.1989975f)));
        assertEquals("closest", hits(knn(2), 1).get(0).id());
        var weighted = ranking.vector(knn(2), context(List.of(2L)));
        assertEquals("followed", hits(weighted, 1).get(0).id());
        // kNN 未纳入的候选不能被加权补回。
        assertEquals("closest", hits(ranking.vector(knn(1), context(List.of(2L))), 1).get(0).id());
    }

    @Test void veryPopularWeakSemanticMatchCannotOvertakeStrongMatch() throws Exception {
        put("relevant", Map.of("authorId", 1));
        put("weak", Map.of("authorId", 2, "vector", List.of(-0.8f, 0.6f),
                "playCount", Long.MAX_VALUE, "likeCount", Long.MAX_VALUE));
        var original = scores(knn(2));
        var hits = hits(ranking.vector(knn(2), context(List.of(2L))), 2);
        assertEquals(List.of("relevant", "weak"), hits.stream().map(Hit::id).toList());
        assertEquals(original.get("relevant"), hits.get(0).score(), 0.00001);
        assertEquals(properties.getVector().getMaxMultiplier(), hits.get(1).score() / original.get("weak"), 0.00001);
    }

    @Test void textFieldWeightsChangeWhichFieldMatchRanksFirst() throws Exception {
        put("title", Map.of("title", "camera", "description", "other"));
        put("description", Map.of("title", "other", "description", "camera"));
        assertEquals("title", hits(text(), 1).get(0).id());
        properties.getFields().setTitle(1);
        properties.getFields().setDescription(10);
        assertEquals("description", hits(text(), 1).get(0).id());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void zeroWeightsAndLatestSortBothLeaveOriginalScoresIntact(boolean vector) throws Exception {
        put("followed", Map.of("authorId", 2, "playCount", 99999, "likeCount", 9999));
        var base = vector ? knn(10) : text();
        double original = scores(base).get("followed");
        var ctx = context(List.of(2L)); ctx.getQuery().setSortByLatest(true);
        assertEquals(original, scores(vector ? ranking.vector(base, ctx) : ranking.keyword(base, ctx)).get("followed"));
        ctx.getQuery().setSortByLatest(false);
        var weights = vector ? properties.getVector() : properties.getKeyword();
        weights.setFollow(0); weights.setPlay(0); weights.setLike(0);
        assertEquals(original, scores(vector ? ranking.vector(base, ctx) : ranking.keyword(base, ctx)).get("followed"));
    }

    Query text() {
        return Query.of(q -> q.multiMatch(m -> m.query("camera").fields(ranking.keywordFields())));
    }

    Query knn(int k) {
        return Query.of(q -> q.knn(n -> n.field("vector").queryVector(List.of(1f, 0f)).k(k).numCandidates(100)));
    }

    SearchContext context(List<Long> follows) {
        return SearchContext.of(HybridRecallServiceTest.context().getQuery(), 7L, follows);
    }

    void put(String id, Map<String, Object> fields) throws Exception {
        Map<String, Object> doc = new HashMap<>(Map.of("title", "camera", "authorId", 1, "vector", List.of(1f, 0f)));
        doc.putAll(fields);
        client.index(i -> i.index(index).id(id).document(doc));
    }

    List<Hit<Void>> hits(Query query, int size) throws Exception {
        client.indices().refresh(r -> r.index(index));
        var response = client.search(s -> s.index(index).size(size).query(query).allowPartialSearchResults(false), Void.class);
        assertEquals(0, response.shards().failed().intValue());
        return response.hits().hits();
    }

    Map<String, Double> scores(Query query) throws Exception {
        return hits(query, 10).stream().collect(Collectors.toMap(Hit::id, Hit::score));
    }
}

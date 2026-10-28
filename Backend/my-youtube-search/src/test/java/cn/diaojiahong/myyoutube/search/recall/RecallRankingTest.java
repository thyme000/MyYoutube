package cn.diaojiahong.myyoutube.search.recall;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecallRankingTest {
    final RecallRankingProperties properties = new RecallRankingProperties();
    final RecallRanking ranking = new RecallRanking(properties);
    final Query relevance = Query.of(q -> q.matchAll(m -> m));

    @Test void externalConfigurationOverridesBothRoutesAndTextWeights() {
        var source = new MapConfigurationPropertySource(Map.of(
                "my-youtube.search.ranking.fields.title", "7",
                "my-youtube.search.ranking.keyword.follow", "0.15",
                "my-youtube.search.ranking.vector.max-multiplier", "1.10"));
        new Binder(source).bind("my-youtube.search.ranking", Bindable.ofInstance(properties));
        properties.validate();
        assertEquals("title^7.0", ranking.keywordFields().get(0));
        assertEquals(0.15, properties.getKeyword().getFollow());
        assertEquals(1.10, properties.getVector().getMaxMultiplier());
        assertEquals(0.02, properties.getKeyword().getPlay());
    }

    @Test void oneFollowConditionCoversAnEntireSnapshotAndRoutesHaveIndependentCoefficients() {
        var ctx = SearchContext.of(HybridRecallServiceTest.context().getQuery(), 7L,
                java.util.stream.LongStream.rangeClosed(1, 205).boxed().toList());
        var keyword = ranking.keyword(relevance, ctx).functionScore();
        var vector = ranking.vector(relevance, ctx).functionScore();
        assertEquals(4, keyword.functions().size());
        assertEquals(205, keyword.functions().get(1).filter().terms().terms().value().size());
        assertEquals(0.20, keyword.functions().get(1).weight());
        assertEquals(0.08, vector.functions().get(1).weight());
        assertEquals(1.5, keyword.maxBoost());
        assertEquals(1.2, vector.maxBoost());
    }

    @Test void disabledBusinessWeightsPreserveTheOriginalScore() {
        properties.setKeyword(new RecallRankingProperties.Weights(0, 0, 0, 1));
        var score = ranking.keyword(relevance, HybridRecallServiceTest.context()).functionScore();
        assertEquals(1, score.functions().size());
        assertEquals(1, score.functions().get(0).weight());
        assertEquals(1, score.maxBoost());
    }

    @Test void latestSortSkipsBothBusinessScorers() {
        var ctx = HybridRecallServiceTest.context(); ctx.getQuery().setSortByLatest(true);
        assertSame(relevance, ranking.keyword(relevance, ctx));
        assertSame(relevance, ranking.vector(relevance, ctx));
    }

    @Test void rejectsInvalidConfigurationBeforeServingQueries() {
        for (double invalid : List.of(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            properties.getKeyword().setFollow(invalid);
            assertThrows(IllegalStateException.class, properties::validate);
        }
        properties.getKeyword().setFollow(0.2);
        properties.getVector().setMaxMultiplier(0.9);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.getVector().setMaxMultiplier(1.2);
        properties.getFields().setTitle(0);
        assertThrows(IllegalStateException.class, properties::validate);
    }
}

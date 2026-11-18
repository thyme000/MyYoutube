package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.recommend.service.impl.RecommendServiceImpl;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.video.api.CfRecommendPort;
import cn.diaojiahong.myyoutube.video.api.PreferencePort;
import cn.diaojiahong.myyoutube.video.api.ViewHistoryPort;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendViewHistoryTest {

    private static final long USER_ID = 7L;

    private final ElasticsearchOperations es = mock(ElasticsearchOperations.class);
    private final PreferencePort preferences = mock(PreferencePort.class);
    private final CfRecommendPort cf = mock(CfRecommendPort.class);
    private final ViewHistoryPort history = mock(ViewHistoryPort.class);
    private final RecommendServiceImpl recommendations = new RecommendServiceImpl(
            preferences, es, mock(FollowPort.class), cf, history, new SimpleMeterRegistry());

    @BeforeEach
    void setup() {
        when(preferences.activeDislikedIds(USER_ID)).thenReturn(List.of(999L));
        when(preferences.topPreferences(USER_ID, 20)).thenReturn(List.of());
    }

    @Test
    void ordinaryRecommendationsRefillAfterFilteringViewedCandidates() {
        List<Long> firstBatch = stubTwoRecallBatches();

        assertEquals(List.of(500L, 501L), recommendations.recommend(USER_ID, "video", null, 1, 2));

        verify(history).viewedIds(new ViewHistoryPort.Candidates(USER_ID, firstBatch));
        verify(history).viewedIds(new ViewHistoryPort.Candidates(USER_ID, List.of(501L, 502L, 503L, 504L)));
        verify(preferences).activeDislikedIds(USER_ID);
    }

    @Test
    void followedAuthorsArePaginatedAfterRemovingViewedCandidates() {
        stubTwoRecallBatches();

        assertEquals(List.of(500L, 501L), recommendations.recommendForFollow(List.of(9L), USER_ID, 1, 2));
        assertEquals(List.of(503L, 504L), recommendations.recommendForFollow(List.of(9L), USER_ID, 2, 2));
    }

    @Test
    void cfKeepsUnviewedCandidatesInTheirOriginalRankOrder() {
        ReflectionTestUtils.setField(recommendations, "cfEnabled", true);
        when(cf.candidates(any())).thenReturn(new CfRecommendPort.CfCandidates("swing", List.of(2L, 3L, 4L)));
        when(es.search(any(NativeQuery.class), eq(ContentDoc.class))).thenAnswer(call -> {
            NativeQuery query = call.getArgument(0);
            return query.getQuery().isBool() ? hits(List.of(4L, 2L, 3L)) : hits(List.of());
        });
        when(history.viewedIds(new ViewHistoryPort.Candidates(USER_ID, List.of(2L, 3L, 4L))))
                .thenReturn(List.of(2L));

        assertEquals(List.of(3L, 4L), recommendations.recommend(USER_ID, "video", null, 1, 2));
    }

    private List<Long> stubTwoRecallBatches() {
        List<Long> firstBatch = LongStream.rangeClosed(1, 500).boxed().toList();
        List<Long> secondBatch = List.of(501L, 502L, 503L, 504L);
        when(es.search(any(NativeQuery.class), eq(ContentDoc.class))).thenAnswer(call -> {
            NativeQuery query = call.getArgument(0);
            int page = query.getPageable().getPageNumber();
            assertEquals(500, query.getPageable().getPageSize());
            Query filter = query.getQuery();
            if (filter.isFunctionScore()) {
                filter = filter.functionScore().query();
            }
            assertEquals(List.of("999"), filter.bool().mustNot().get(0).ids().values());
            return hits(page == 0 ? firstBatch : secondBatch);
        });
        when(history.viewedIds(new ViewHistoryPort.Candidates(USER_ID, firstBatch)))
                .thenReturn(firstBatch.subList(0, 499));
        when(history.viewedIds(new ViewHistoryPort.Candidates(USER_ID, secondBatch))).thenReturn(List.of(502L));
        return firstBatch;
    }

    @SuppressWarnings("unchecked")
    private SearchHits<ContentDoc> hits(List<Long> ids) {
        SearchHits<ContentDoc> hits = mock(SearchHits.class);
        List<SearchHit<ContentDoc>> entries = ids.stream().map(id -> {
            ContentDoc doc = new ContentDoc();
            doc.setId(id);
            SearchHit<ContentDoc> hit = mock(SearchHit.class);
            when(hit.getContent()).thenReturn(doc);
            return hit;
        }).toList();
        when(hits.getSearchHits()).thenReturn(entries);
        return hits;
    }
}

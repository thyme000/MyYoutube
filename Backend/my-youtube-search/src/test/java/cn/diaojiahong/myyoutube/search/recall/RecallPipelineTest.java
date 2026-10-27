package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoPageQuery;
import cn.diaojiahong.myyoutube.search.vector.QueryVectorRecall;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(5)
class RecallPipelineTest {
    final RecallTestSupport runtime = new RecallTestSupport(150);
    final RecallProperties settings = new RecallProperties();
    final VectorProperties vectors = new VectorProperties();
    final RecallSearchClient es = mock(RecallSearchClient.class);
    final QueryVectorRecall queryVector = mock(QueryVectorRecall.class);
    final KeywordRecallStrategy keyword = runtime.proxy(new KeywordRecallStrategy(es, vectors, new RecallRanking(new RecallRankingProperties())));
    final VectorRecallStrategy vector = runtime.proxy(new VectorRecallStrategy(queryVector, vectors));
    final RecallPipeline pipeline = new RecallPipeline(List.of(keyword, vector), new RrfFuser(vectors),
            mock(LatestFuser.class), settings, runtime.metrics);

    @AfterEach void close() { runtime.close(); }

    @Test void timelyResultsUseRrfAndDeduplicate() throws Exception {
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("k", "shared")));
        when(queryVector.recall(any())).thenReturn(List.of("v", "shared"));
        assertEquals(List.of("shared", "k", "v"), pipeline.execute(context()));
    }

    @Test void slowVectorContinuesAfterKeywordIsReturnedAndCannotAlterTheResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        when(queryVector.recall(any())).thenAnswer(call -> {
            entered.countDown();
            try { release.await(); return List.of("late"); }
            finally { finished.countDown(); }
        });
        try {
            List<String> result = pipeline.execute(context());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("keyword"), result);
            assertEquals(1, finished.getCount());
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("keyword"), result);
        } finally { release.countDown(); }
    }

    @Test void bothFailedRoutesAreAnErrorAndBothEmptyRoutesAreValid() throws Exception {
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of()));
        when(queryVector.recall(any())).thenReturn(List.of());
        assertEquals(List.of(), pipeline.execute(context()));
        when(es.page(any(), anyInt())).thenThrow(new java.io.IOException("ES down"));
        when(queryVector.recall(any())).thenThrow(new java.io.IOException("model down"));
        assertThrows(DependencyException.class, () -> pipeline.execute(context()));
    }

    @Test void programmingFailuresAreNotSilentlyDowngraded() throws Exception {
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        when(queryVector.recall(any())).thenThrow(new IllegalArgumentException("bug"));
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(CompletionException.class, () -> pipeline.execute(context())).getCause());
    }

    @Test void fullVectorPoolRejectsWithoutUsingTheRequestThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(1);
        Runnable block = () -> { occupied.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        runtime.vectorExecutor.execute(block);
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        runtime.vectorExecutor.execute(block);
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        try {
            assertEquals(List.of("keyword"), pipeline.execute(context()));
            verifyNoInteractions(queryVector);
            assertEquals(1, runtime.metrics.counter(MetricNames.ASYNC_REJECTED, "resource", "vector-recall").count());
        } finally { release.countDown(); }
    }

    @Test void disabledAndBlankQueriesDoNotStartTheVectorRoute() throws Exception {
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        vectors.setEnabled(false);
        assertEquals(List.of("keyword"), pipeline.execute(context()));
        vectors.setEnabled(true);
        SearchVideoPageQuery query = new SearchVideoPageQuery(); query.setKeyword(" ");
        assertEquals(List.of("keyword"), pipeline.execute(SearchContext.of(query, 7L, List.of())));
        verifyNoInteractions(queryVector);
    }

    @Test void followSnapshotRemainsImmutableAndIsSharedByBothRoutes() throws Exception {
        List<Long> followed = new java.util.ArrayList<>(List.of(11L));
        SearchContext context = SearchContext.of(context().getQuery(), 7L, followed);
        followed.clear();
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        when(queryVector.recall(context)).thenAnswer(call -> {
            assertEquals(List.of(11L), context.getFollowIds());
            return List.of("vector");
        });
        assertEquals(List.of("keyword", "vector"), pipeline.execute(context));
        assertThrows(UnsupportedOperationException.class, () -> context.getFollowIds().add(12L));
    }

    @Test void noStrategiesReturnEmpty() {
        assertEquals(List.of(), new RecallPipeline(List.of(), new RrfFuser(vectors), mock(LatestFuser.class), settings, runtime.metrics).execute(context()));
    }

    static SearchContext context() {
        SearchVideoPageQuery query = new SearchVideoPageQuery(); query.setKeyword("搜索");
        return SearchContext.of(query, 7L, List.of());
    }
}

package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
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
class HybridRecallServiceTest {
    final RecallTestSupport runtime = new RecallTestSupport(150);

    final RecallProperties settings = new RecallProperties();

    final VectorProperties vectors = new VectorProperties();

    final RecallSearchClient es = mock(RecallSearchClient.class);

    final QueryVectorRecall queryVector = mock(QueryVectorRecall.class);

    final KeywordRecallStrategy keyword = runtime.proxy(new KeywordRecallStrategy(
            es,
            vectors,
            new RecallRanking(new RecallRankingProperties())));

    final VectorRecallStrategy vector = runtime.proxy(new VectorRecallStrategy(queryVector, vectors));

    final HybridRecallService hybrid = new HybridRecallService(List.of(keyword, vector), new RrfFuser(vectors),
            settings, runtime.metrics);

    @AfterEach
    void close() {
        runtime.close();
    }

    @Test
    void timelyResultsUseRrfAndDeduplicate() throws Exception {
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("k", "shared")));
        when(queryVector.recall(any())).thenReturn(List.of("v", "shared"));
        assertEquals(List.of("shared", "k", "v"), hybrid.recall(context(), 500, ids -> ids).ids());
    }

    @Test
    void slowVectorContinuesAfterKeywordIsReturnedAndCannotAlterTheResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        when(queryVector.recall(any())).thenAnswer(call -> {
            entered.countDown();
            try {
                release.await();
                return List.of("late");
            } finally {
                finished.countDown();
            }
        });
        try {
            List<String> result = hybrid.recall(context(), 500, ids -> ids).ids();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("keyword"), result);
            assertEquals(1, finished.getCount());
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("keyword"), result);
        } finally {
            release.countDown();
        }
    }

    @Test
    void bothFailedRoutesAreAnErrorAndBothEmptyRoutesAreValid() throws Exception {
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of()));
        when(queryVector.recall(any())).thenReturn(List.of());
        assertEquals(List.of(), hybrid.recall(context(), 500, ids -> ids).ids());
        when(es.page(any(), anyInt())).thenThrow(new java.io.IOException("ES down"));
        when(queryVector.recall(any())).thenThrow(new java.io.IOException("model down"));
        assertThrows(DependencyException.class, () -> hybrid.recall(context(), 500, ids -> ids).ids());
    }

    @Test
    void programmingFailuresAreNotSilentlyDowngraded() throws Exception {
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        when(queryVector.recall(any())).thenThrow(new IllegalArgumentException("bug"));
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(CompletionException.class, () -> hybrid.recall(context(), 500, ids -> ids).ids())
                        .getCause());
    }

    @Test
    void fullVectorPoolRejectsWithoutUsingTheRequestThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(1);
        Runnable block = () -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        runtime.vectorExecutor.execute(block);
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        runtime.vectorExecutor.execute(block);
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        try {
            assertEquals(List.of("keyword"), hybrid.recall(context(), 500, ids -> ids).ids());
            verifyNoInteractions(queryVector);
            assertEquals(1, runtime.metrics.counter(MetricNames.ASYNC_REJECTED, "resource", "vector-recall").count());
        } finally {
            release.countDown();
        }
    }

    @Test
    void disabledAndBlankQueriesDoNotStartTheVectorRoute() throws Exception {
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        vectors.setEnabled(false);
        assertEquals(List.of("keyword"), hybrid.recall(context(), 500, ids -> ids).ids());
        vectors.setEnabled(true);
        ContentSearchCursorQuery query = new ContentSearchCursorQuery();
        query.setKeyword(" ");
        assertEquals(List.of("keyword"), hybrid.recall(SearchContext.of(query, 7L, List.of()), 500, ids -> ids).ids());
        verifyNoInteractions(queryVector);
    }

    @Test
    void followSnapshotRemainsImmutableAndIsSharedByBothRoutes() throws Exception {
        List<Long> followed = new java.util.ArrayList<>(List.of(11L));
        SearchContext context = SearchContext.of(context().getQuery(), 7L, followed);
        followed.clear();
        when(es.page(any(), anyInt())).thenReturn(RecallBatch.exhausted(List.of("keyword")));
        when(queryVector.recall(context)).thenAnswer(call -> {
            assertEquals(List.of(11L), context.getFollowIds());
            return List.of("vector");
        });
        assertEquals(List.of("keyword", "vector"), hybrid.recall(context, 500, ids -> ids).ids());
        assertThrows(UnsupportedOperationException.class, () -> context.getFollowIds().add(12L));
    }

    @Test
    void noStrategiesReturnEmpty() {
        assertEquals(List.of(), new HybridRecallService(List.of(), new RrfFuser(vectors), settings, runtime.metrics)
                .recall(context(), 500, ids -> ids)
                .ids());
    }

    static SearchContext context() {
        ContentSearchCursorQuery query = new ContentSearchCursorQuery();
        query.setKeyword("搜索");
        return SearchContext.of(query, 7L, List.of());
    }
}

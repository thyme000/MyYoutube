package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.config.RecallConfig;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoPageQuery;
import cn.diaojiahong.myyoutube.search.vector.QueryVectorRecall;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Timeout(10)
class RecallPipelineTest {
    final VectorProperties properties = new VectorProperties();
    final RecallProperties recallProperties = new RecallProperties();
    final QueryVectorRecall vectors = mock(QueryVectorRecall.class);
    final KeywordRecallStrategy keyword = mock(KeywordRecallStrategy.class);
    final FollowPort follows = mock(FollowPort.class);
    final ThreadPoolTaskExecutor keywordExecutor = new RecallConfig().recallExecutor();
    final ThreadPoolTaskExecutor vectorExecutor;
    final RecallPipeline pipeline;

    RecallPipelineTest() {
        recallProperties.setWaitMillis(160);
        recallProperties.setTimeoutMillis(2000);
        properties.setQueryMaxConcurrent(1);
        properties.setQueryQueueCapacity(1);
        vectorExecutor = new RecallConfig().queryVectorExecutor(properties);
        when(keyword.shouldRun(any())).thenReturn(true);
        when(keyword.executorName()).thenReturn("recallExecutor");
        when(keyword.needsFollowIds()).thenReturn(true);
        when(follows.getFollowIds(any())).thenReturn(List.of());
        pipeline = new RecallPipeline(List.of(keyword, new VectorRecallStrategy(vectors, properties)),
                new RrfFuser(properties), mock(LatestFuser.class), follows, recallProperties, new SimpleMeterRegistry(),
                Map.of("recallExecutor", keywordExecutor, "queryVectorExecutor", vectorExecutor));
    }

    @AfterEach void close() { keywordExecutor.shutdown(); vectorExecutor.shutdown(); }

    @Test void blockedVectorDoesNotDelayKeywordAndItsSlotCanBeReused() throws Exception {
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return List.of("late"); }
            finally { stopped.countDown(); }
        });
        when(keyword.recall(any(), anyLong())).thenAnswer(call -> {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            return List.of("keyword");
        });
        long start = System.nanoTime();
        assertEquals(List.of("keyword"), pipeline.execute(context()));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 600);
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        doReturn(List.of("fresh")).when(vectors).recall(anyString(), any(), anyLong());
        assertEquals(List.of("keyword", "fresh"), pipeline.execute(context()));
    }

    @Test void timelyResultsUseRrfAndDeduplicate() throws Exception {
        when(keyword.recall(any(), anyLong())).thenReturn(List.of("k", "shared"));
        when(vectors.recall(anyString(), any(), anyLong())).thenReturn(List.of("v", "shared"));
        assertEquals(List.of("shared", "k", "v"), pipeline.execute(context()));
    }

    @Test void queuedVectorUsesOriginalRequestBudgetAndIsRemovedBeforeExecution() throws Exception {
        var release = new CountDownLatch(1);
        var occupied = new CountDownLatch(1);
        vectorExecutor.execute(() -> {
            occupied.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        when(keyword.recall(any(), anyLong())).thenReturn(List.of("keyword"));
        try {
            long start = System.nanoTime();
            assertEquals(List.of("keyword"), pipeline.execute(context()));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 600);
            assertEquals(0, vectorExecutor.getQueueSize());
            verifyNoInteractions(vectors);
        } finally { release.countDown(); }
    }

    @Test void saturatedVectorPoolRejectsWithoutRunningOnRequestThread() throws Exception {
        recallProperties.setWaitMillis(1000);
        var release = new CountDownLatch(1);
        Runnable blocker = () -> { try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } };
        vectorExecutor.execute(blocker);
        vectorExecutor.execute(blocker);
        when(keyword.recall(any(), anyLong())).thenReturn(List.of("keyword"));
        try {
            long start = System.nanoTime();
            assertEquals(List.of("keyword"), pipeline.execute(context()));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 600);
            verifyNoInteractions(vectors);
        } finally { release.countDown(); }
    }

    @Test void lateFirstVectorIsUsedWhenNeitherRouteMeetsWindow() throws Exception {
        recallProperties.setWaitMillis(60);
        var stopped = new CountDownLatch(1);
        when(keyword.recall(any(), anyLong())).thenAnswer(call -> {
            try { new CountDownLatch(1).await(); return List.of(); }
            finally { stopped.countDown(); }
        });
        when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
            Thread.sleep(140); return List.of("late-vector");
        });
        assertEquals(List.of("late-vector"), pipeline.execute(context()));
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
    }

    @Test void slowKeywordCannotBlockTimelyVector() throws Exception {
        var entered = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        when(keyword.recall(any(), anyLong())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return List.of(); }
            finally { stopped.countDown(); }
        });
        when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
            assertTrue(entered.await(1, TimeUnit.SECONDS)); return List.of("vector");
        });
        assertEquals(List.of("vector"), pipeline.execute(context()));
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        assertTrue(keywordExecutor.submit(() -> true).get(1, TimeUnit.SECONDS));
    }

    @Test void lateFirstKeywordIsUsedWhenNeitherRouteMeetsWindow() throws Exception {
        recallProperties.setWaitMillis(50);
        var stopped = new CountDownLatch(1);
        when(keyword.recall(any(), anyLong())).thenAnswer(call -> {
            Thread.sleep(140); return List.of("late-keyword");
        });
        when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
            try { new CountDownLatch(1).await(); return List.of(); }
            finally { stopped.countDown(); }
        });
        assertEquals(List.of("late-keyword"), pipeline.execute(context()));
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
    }

    @Test void emptyOrFailedRouteDoesNotHideLaterCandidates() throws Exception {
        recallProperties.setWaitMillis(50);
        when(keyword.recall(any(), anyLong())).thenReturn(List.of()).thenThrow(new IllegalStateException("ES unavailable"));
        when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
            Thread.sleep(100); return List.of("vector");
        });
        assertEquals(List.of("vector"), pipeline.execute(context()));
        assertEquals(List.of("vector"), pipeline.execute(context()));
    }

    @Test void allEmptyRoutesReturnWithoutWaitingForHardTimeout() throws Exception {
        when(keyword.recall(any(), anyLong())).thenReturn(List.of());
        when(vectors.recall(anyString(), any(), anyLong())).thenReturn(List.of());
        assertEquals(List.of(), pipeline.execute(context()));
    }

    @Test void hardTimeoutCancelsBothBlockedRoutes() throws Exception {
        recallProperties.setWaitMillis(50); recallProperties.setTimeoutMillis(250);
        var entered = new CountDownLatch(2); var stopped = new CountDownLatch(2);
        when(keyword.recall(any(), anyLong())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return List.of(); }
            finally { stopped.countDown(); }
        });
        when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return List.of(); }
            finally { stopped.countDown(); }
        });
        assertEquals(List.of(), pipeline.execute(context()));
        assertEquals(0, entered.getCount());
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
    }

    @Test void hardTimeoutIncludesTimeBeforePipelineExecution() throws Exception {
        recallProperties.setTimeoutMillis(160);
        var ctx = context(); Thread.sleep(200);
        assertEquals(List.of(), pipeline.execute(ctx));
        verifyNoInteractions(vectors);
        verify(keyword, never()).recall(any(), anyLong());
        verifyNoInteractions(follows);
    }

    @Test void disabledOrBlankQueriesSkipVectorGeneration() throws Exception {
        when(keyword.recall(any(), anyLong())).thenReturn(List.of("keyword"));
        properties.setEnabled(false);
        assertEquals(List.of("keyword"), pipeline.execute(context()));
        properties.setEnabled(true);
        var query = new SearchVideoPageQuery(); query.setKeyword("  ");
        assertEquals(List.of("keyword"), pipeline.execute(SearchContext.of(query, 7L)));
        verifyNoInteractions(vectors);
    }

    @Test void multipleDependentStrategiesShareOneFollowSnapshot() throws Exception {
        when(follows.getFollowIds(7L)).thenReturn(List.of(11L, 12L));
        var entered = new CountDownLatch(2);
        RecallStrategy first = dependent(ctx -> {
            entered.countDown(); assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(11L, 12L), ctx.getFollowIds()); return List.of("first");
        });
        RecallStrategy second = dependent(ctx -> {
            entered.countDown(); assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(11L, 12L), ctx.getFollowIds()); return List.of("second");
        });
        var ctx = context();
        assertEquals(List.of("first", "second"), pipeline(List.of(first, second)).execute(ctx));
        assertEquals(List.of(11L, 12L), ctx.getFollowIds());
        verify(follows, times(1)).getFollowIds(7L);
    }

    @Test void failedFollowLookupIsNotRetriedByOtherStrategies() {
        when(follows.getFollowIds(7L)).thenThrow(new IllegalStateException("follow unavailable"));
        RecallStrategy first = dependent(ctx -> { fail("failed dependency must skip recall"); return List.of(); });
        RecallStrategy second = dependent(ctx -> { fail("failed dependency must skip recall"); return List.of(); });
        assertEquals(List.of(), pipeline(List.of(first, second)).execute(context()));
        verify(follows, times(1)).getFollowIds(7L);
    }

    @Test void blockedFollowLookupDoesNotDelayVectorOrResponseSnapshot() throws Exception {
        var entered = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        when(follows.getFollowIds(7L)).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return List.of(11L); }
            finally { stopped.countDown(); }
        });
        when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
            assertTrue(entered.await(1, TimeUnit.SECONDS)); return List.of("vector");
        });
        var ctx = context();
        assertEquals(List.of("vector"), pipeline.execute(ctx));
        assertEquals(List.of(), ctx.getFollowIds());
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        verify(keyword, never()).recall(any(), anyLong());
        verify(follows, times(1)).getFollowIds(7L);
    }

    @Test void latestSortSkipsFollowRpcAndNoActiveStrategiesReturnEmpty() {
        RecallStrategy strategy = dependent(ctx -> {
            assertEquals(List.of(), ctx.getFollowIds()); return List.of();
        });
        var ctx = context(); ctx.getQuery().setSortByLatest(true);
        assertTrue(ctx.isSortByLatest());
        pipeline(List.of(strategy)).execute(ctx);
        assertEquals(List.of(), pipeline(List.of()).execute(context()));
        verifyNoInteractions(follows);
    }

    @Test void saturatedKeywordPoolRejectsWithoutBlockingVector() throws Exception {
        var release = new CountDownLatch(1);
        Runnable blocker = () -> { try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } };
        int capacity = keywordExecutor.getMaxPoolSize() + keywordExecutor.getThreadPoolExecutor().getQueue().remainingCapacity();
        for (int i = 0; i < capacity; i++) keywordExecutor.execute(blocker);
        when(vectors.recall(anyString(), any(), anyLong())).thenReturn(List.of("vector"));
        try {
            assertEquals(List.of("vector"), pipeline.execute(context()));
            verify(keyword, never()).recall(any(), anyLong());
        } finally { release.countDown(); }
    }

    @Test void interruptionCancelsRunningVectorAndReleasesSlot() throws Exception {
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        when(keyword.recall(any(), anyLong())).thenReturn(List.of("keyword"));
        when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return List.of(); }
            finally { stopped.countDown(); }
        });
        var caller = Executors.newSingleThreadExecutor();
        try {
            var response = caller.submit(() -> pipeline.execute(context()));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            response.cancel(true);
            assertTrue(stopped.await(1, TimeUnit.SECONDS));
        } finally { caller.shutdownNow(); }
    }

    @Test void searchServiceReturnsKeywordPageWhileVectorIsBlocked() throws Exception {
        when(vectors.recall(anyString(), any(), anyLong())).thenAnswer(call -> {
            new CountDownLatch(1).await(); return List.of("late");
        });
        when(keyword.recall(any(), anyLong())).thenReturn(List.of("1"));
        var summaries = mock(cn.diaojiahong.myyoutube.video.api.ContentSummaryPort.class);
        var summary = new cn.diaojiahong.myyoutube.video.api.dto.ContentSummary();
        summary.setId(1L); summary.setAuthorId(7L); summary.setType("dynamic"); summary.setTitle("关键词命中");
        when(summaries.listByIdsOrdered(any())).thenReturn(List.of(summary));
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.SetOperations<String, String> searches = mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForSet()).thenReturn(searches);
        when(searches.isMember(anyString(), anyString())).thenReturn(true);
        var search = new cn.diaojiahong.myyoutube.search.service.impl.SearchServiceImpl(
                mock(org.springframework.data.elasticsearch.core.ElasticsearchOperations.class), follows,
                mock(cn.diaojiahong.myyoutube.video.api.ContentInternalPort.class), summaries,
                mock(cn.diaojiahong.myyoutube.user.api.UserPort.class), mock(cn.diaojiahong.myyoutube.common.utils.S3StorageUtil.class),
                redis, mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class), pipeline);
        cn.diaojiahong.myyoutube.common.utils.UserContext.setUser("7");
        try {
            long start = System.nanoTime();
            var page = search.searchVideo(context().getQuery());
            assertEquals(List.of("1"), page.getList().stream().map(item -> item.getId()).toList());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 600);
        } finally { cn.diaojiahong.myyoutube.common.utils.UserContext.removeUser(); }
    }

    static SearchContext context() {
        var query = new SearchVideoPageQuery(); query.setKeyword("搜索");
        return SearchContext.of(query, 7L);
    }

    RecallPipeline pipeline(List<RecallStrategy> strategies) {
        return new RecallPipeline(strategies, new RrfFuser(properties), mock(LatestFuser.class), follows,
                recallProperties, new SimpleMeterRegistry(),
                Map.of("recallExecutor", keywordExecutor, "queryVectorExecutor", vectorExecutor));
    }

    interface RecallCall { List<String> run(SearchContext ctx) throws Exception; }

    static RecallStrategy dependent(RecallCall call) {
        return new RecallStrategy() {
            @Override public boolean shouldRun(SearchContext ctx) { return true; }
            @Override public boolean needsFollowIds() { return true; }
            @Override public List<String> recall(SearchContext ctx, long deadline) throws Exception { return call.run(ctx); }
        };
    }
}

package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.hot.HotSearchGroup;
import cn.diaojiahong.myyoutube.search.hot.HotSearchService;
import cn.diaojiahong.myyoutube.search.vector.QueryVectorRecall;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryVectorCacheTest {
    final QueryEmbeddingPort embeddings = mock(QueryEmbeddingPort.class);

    final RecallSearchClient search = mock(RecallSearchClient.class);

    final HotSearchService hot = mock(HotSearchService.class);

    final QueryVectorRecall recall = new QueryVectorRecall(embeddings, search, new VectorProperties(),
            new RecallRanking(new RecallRankingProperties()), hot, new RecallProperties());

    SearchContext context(String text) {
        var query = new ContentSearchCursorQuery();
        query.setKeyword(text);
        query.setCategory("video");
        return SearchContext.of(query, 7L, List.of());
    }

    long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    }

    void generation() {
        when(embeddings.embed(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(
                List.of(List.of(0f, 1f)),
                2,
                "current"));
    }

    @Test
    void exactNameUsesSavedVectorWithCurrentVersionAndOriginalFilters() throws Exception {
        when(hot.cachedGroup(eq("手机夜景"))).thenReturn(new HotSearchGroup("1", "手机夜景", List.of(1f, 0f), "current"));
        when(embeddings.info(any())).thenReturn(new QueryEmbeddingPort.Info("current", 2));
        when(search.search(any())).thenReturn(List.of("42"));
        assertEquals(List.of("42"), recall.recall(context("手机夜景")));
        verify(embeddings, never()).embed(any(), any());
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(request.capture());
        String json = request.getValue().toString();
        assertTrue(json.contains("published"));
        assertTrue(json.contains("public"));
        assertTrue(json.contains("current"));
        assertTrue(json.contains("video"));
        assertTrue(json.contains("[1.0,0.0]"));
    }

    @Test
    void cacheMissDoesNotNeedVersionRpc() throws Exception {
        generation();
        recall.recall(context("普通搜索"));
        verify(embeddings, never()).info(any());
        verify(embeddings).embed(any(), any());
    }

    @Test
    void similarButDifferentTextDoesNotReuseTheGroupVector() throws Exception {
        generation();
        when(hot.cachedGroup(eq("Java 面试"))).thenReturn(new HotSearchGroup("1", "Java 入门", List.of(1f, 0f), "current"));
        recall.recall(context("Java 面试"));
        verify(embeddings, never()).info(any());
        ArgumentCaptor<QueryEmbeddingPort.Query> query = ArgumentCaptor.forClass(QueryEmbeddingPort.Query.class);
        verify(embeddings).embed(query.capture(), any());
        assertEquals("Java 面试", query.getValue().text());
    }

    @Test
    void versionChangesInvalidateSavedVectorAndGenerateCurrentQuery() throws Exception {
        generation();
        when(hot.cachedGroup(anyString())).thenReturn(new HotSearchGroup("1", "搜索", List.of(1f, 0f), "old"));
        when(embeddings.info(any())).thenReturn(new QueryEmbeddingPort.Info("current", 2));
        recall.recall(context("搜索"));
        verify(embeddings).embed(any(), any());
    }

    @Test
    void metadataFailureFallsBackToOriginalEmbeddingRoute() throws Exception {
        generation();
        when(hot.cachedGroup(anyString())).thenReturn(new HotSearchGroup("1", "搜索", List.of(1f, 0f), "current"));
        when(embeddings.info(any())).thenThrow(new IllegalStateException("metadata unavailable"));
        recall.recall(context("搜索"));
        verify(embeddings).embed(any(), any());
    }
}

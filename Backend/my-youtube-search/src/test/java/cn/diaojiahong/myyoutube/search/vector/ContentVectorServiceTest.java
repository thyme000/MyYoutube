package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentVectorServiceTest {
    final LlmPort llm = mock(LlmPort.class);
    final ElasticsearchClient client = mock(ElasticsearchClient.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final VectorProperties properties = new VectorProperties();
    final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    final ContentVectorService service = new ContentVectorService(llm, client, redis, properties, executor);

    @Test void embedsAllThreeFieldsOnlyAfterQueuedTaskRunsAndWritesOnlyVectorFields() throws Exception {
        when(llm.embeddings(any())).thenReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(1.0f, 2.0f)), 2, "model:2"));
        service.embedAsync(1L, " 标题 ", " 简介 ", " 内容摘要 ");
        verifyNoInteractions(llm, client);
        runQueuedTask();

        var input = ArgumentCaptor.forClass(LlmPort.EmbeddingsRequest.class);
        verify(llm).embeddings(input.capture());
        assertEquals(List.of("标题\n简介\n内容摘要"), input.getValue().getTexts());
        var update = captureUpdate();
        assertEquals("content", update.index());
        assertEquals("1", update.id());
        var fields = update.doc().to(Map.class);
        assertEquals(Set.of("vector", "vectorVersion"), fields.keySet());
        assertEquals("model:2", fields.get("vectorVersion"));
    }

    @Test void emptySummaryIsAllowedAndModelFailureDoesNotWriteOrDeleteDocument() {
        when(llm.embeddings(any())).thenThrow(new IllegalStateException("model unavailable"));
        service.embedAsync(1L, "标题", null, " ");
        assertDoesNotThrow(this::runQueuedTask);
        var input = ArgumentCaptor.forClass(LlmPort.EmbeddingsRequest.class);
        verify(llm).embeddings(input.capture());
        assertEquals(List.of("标题"), input.getValue().getTexts());
        verifyNoInteractions(client);
    }

    @Test void disabledOrEmptyInputDoesNotScheduleEmbedding() {
        service.embedAsync(1L, " ", null, "");
        properties.setEnabled(false);
        service.embedAsync(1L, "标题", "简介", "摘要");
        verifyNoInteractions(executor, llm, client);
    }

    @Test void knnFiltersPublicationVisibilityCategoryAndModelVersion() throws Exception {
        when(client.search(any(Function.class), eq(Void.class))).thenReturn(emptySearch());
        assertEquals(List.of(), service.knnSearch(new ContentVectorService.QueryVector(List.of(1.0f), "model:1"), "tech", 5));
        var request = captureSearch();
        var terms = request.knn().get(0).filter().get(0).bool().must().stream()
                .collect(Collectors.toMap(query -> query.term().field(), query -> query.term().value().stringValue()));
        assertEquals(Map.of("status", "published", "visibility", "public", "category", "tech", "vectorVersion", "model:1"), terms);
    }

    @Test void rebuildUsesStoredSummaryAndSelectsOnlyPublishedPublicDocuments() throws Exception {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(llm.embeddings(any())).thenReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(1.0f)), 1, "model:1"));
        SearchResponse<Void> batch = SearchResponse.of(s -> s.took(0).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(hit -> hit.index("content").id("1"))));
        when(client.search(any(Function.class), eq(Void.class))).thenReturn(batch, emptySearch());
        GetResponse<Map> stored = mock(GetResponse.class);
        when(stored.found()).thenReturn(true);
        when(stored.source()).thenReturn(Map.of("title", "标题", "description", "简介", "summary", "摘要"));
        when(client.get(any(Function.class), eq(Map.class))).thenReturn(stored);

        assertTrue(service.startRebuild());
        runQueuedTask();
        assertFalse(service.isRebuildRunning());
        var input = ArgumentCaptor.forClass(LlmPort.EmbeddingsRequest.class);
        verify(llm, times(2)).embeddings(input.capture());
        assertEquals(List.of("标题\n简介\n摘要"), input.getAllValues().get(1).getTexts());
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> requests = ArgumentCaptor.forClass(Function.class);
        verify(client, times(2)).search(requests.capture(), eq(Void.class));
        var query = requests.getAllValues().get(0).apply(new SearchRequest.Builder()).build().query().bool();
        assertEquals(Map.of("status", "published", "visibility", "public"), query.filter().stream()
                .collect(Collectors.toMap(term -> term.term().field(), term -> term.term().value().stringValue())));
        verify(values).set("search:vector:rebuild:last_id", "1");
        verify(redis).delete("search:vector:rebuild:last_id");
    }

    private void runQueuedTask() {
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
    }

    private UpdateRequest<Void, JsonData> captureUpdate() throws Exception {
        ArgumentCaptor<Function<UpdateRequest.Builder<Void, JsonData>, ObjectBuilder<UpdateRequest<Void, JsonData>>>> request = ArgumentCaptor.forClass(Function.class);
        verify(client).update(request.capture(), eq(Void.class));
        return request.getValue().apply(new UpdateRequest.Builder<>()).build();
    }

    private SearchRequest captureSearch() throws Exception {
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> request = ArgumentCaptor.forClass(Function.class);
        verify(client).search(request.capture(), eq(Void.class));
        return request.getValue().apply(new SearchRequest.Builder()).build();
    }

    private SearchResponse<Void> emptySearch() {
        return SearchResponse.of(s -> s.took(0).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0)).hits(hits -> hits.hits(List.of())));
    }
}

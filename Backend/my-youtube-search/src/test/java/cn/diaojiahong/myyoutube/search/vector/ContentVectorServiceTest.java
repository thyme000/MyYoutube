package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.common.mq.message.ContentVectorMessage;
import cn.diaojiahong.myyoutube.mq.listener.ContentVectorListener;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentVectorServiceTest {
    final LlmPort llm = mock(LlmPort.class);
    final ElasticsearchClient client = mock(ElasticsearchClient.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final VectorProperties properties = new VectorProperties();
    final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    final IdempotentGuard guard = mock(IdempotentGuard.class);
    final ContentVectorPublisher publisher = mock(ContentVectorPublisher.class);
    final IdempotentGuard.Lease lease = mock(IdempotentGuard.Lease.class);
    final ContentVectorBuilder builder = new ContentVectorBuilder(llm, client, publisher, guard, new VectorProperties());
    final ContentVectorService service = new ContentVectorService(llm, client, redis, properties, executor, guard, builder);
    final Map<String, String> checkpoints = new java.util.HashMap<>();
    final ValueOperations<String, String> values = mock(ValueOperations.class);

    @BeforeEach void leases() throws Exception {
        when(client.bulk(any(BulkRequest.class))).thenAnswer(call -> {
            BulkRequest request = call.getArgument(0);
            return BulkResponse.of(response -> response.took(1).errors(false)
                    .items(request.operations().stream().map(operation -> BulkResponseItem.of(
                            item -> item.operationType(OperationType.Update)
                                    .index("content").id(operation.update().id()).status(200))).toList()));
        });
        when(lease.acquired()).thenReturn(true);
        when(lease.token()).thenReturn("RUNNING:test");
        when(guard.acquire(anyString(), anyLong())).thenReturn(lease);
        when(guard.acquireAll(anyList(), anyLong())).thenReturn(lease);
        when(guard.currentLease()).thenReturn(lease);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> checkpoints.get(call.getArgument(0)));
        doAnswer(call -> {
            List<String> keys = call.getArgument(1);
            Object[] args = (Object[]) call.getRawArguments()[2];
            checkpoints.put(keys.get(1), args[1].toString());
            return 1L;
        }).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class));
    }

    @Test void consumerCompletesAllThreeFieldsAndWritesOnlyVectorFieldsBeforeReturning() throws Exception {
        storedContent(Map.of("status", "published", "visibility", "public", "title", " 标题 ", "description", " 简介 ", "summary", " 内容摘要 "));
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(1.0f, 2.0f)), 2, "model:2"));
        new ContentVectorListener(service).onMessage(new ContentVectorMessage(1L));
        verifyNoInteractions(executor);

        var input = ArgumentCaptor.forClass(LlmPort.EmbeddingsRequest.class);
        verify(llm).embeddings(input.capture(), any(feign.Request.Options.class));
        assertEquals(List.of("标题\n简介\n内容摘要"), input.getValue().getTexts());
        var update = captureUpdate();
        assertEquals("content", update.index());
        assertEquals("1", update.id());
        var fields = update.doc().to(Map.class);
        assertEquals(Set.of("vector", "vectorVersion"), fields.keySet());
        assertEquals("model:2", fields.get("vectorVersion"));
    }

    @Test void emptySummaryIsAllowedAndModelFailureReachesMqListener() throws Exception {
        storedContent(Map.of("status", "published", "visibility", "public", "title", "标题", "summary", " "));
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenThrow(new IllegalStateException("model unavailable"));
        assertThrows(IllegalStateException.class, () -> new ContentVectorListener(service).onMessage(new ContentVectorMessage(1L)));
        var input = ArgumentCaptor.forClass(LlmPort.EmbeddingsRequest.class);
        verify(llm).embeddings(input.capture(), any(feign.Request.Options.class));
        assertEquals(List.of("标题"), input.getValue().getTexts());
        verify(client, never()).update(any(Function.class), eq(Void.class));
    }

    @Test void missingPrivateBannedCompletedAndEmptyDocumentsDoNotCallModel() throws Exception {
        storedContent(Map.of("status", "published", "visibility", "private", "title", "标题"));
        service.embedContent(1L);
        storedContent(Map.of("status", "banned", "visibility", "public", "title", "标题"));
        service.embedContent(1L);
        storedContent(Map.of("status", "published", "visibility", "public", "title", "标题", "vectorVersion", "model:2"));
        service.embedContent(1L);
        storedContent(Map.of("status", "published", "visibility", "public", "title", " "));
        service.embedContent(1L);
        when(client.get(any(Function.class), eq(Map.class))).thenReturn(mock(GetResponse.class));
        service.embedContent(1L);
        verifyNoInteractions(executor, llm);
        verify(client, never()).update(any(Function.class), eq(Void.class));
    }

    @Test void esReadAndWriteFailuresReachConsumerAndAllowLaterRetry() throws Exception {
        var listener = new ContentVectorListener(service);
        when(client.get(any(Function.class), eq(Map.class))).thenThrow(new IOException("ES unavailable"));
        assertThrows(IllegalStateException.class, () -> listener.onMessage(new ContentVectorMessage(1L)));
        verifyNoInteractions(llm);
        GetResponse<Map> stored = mock(GetResponse.class);
        when(stored.found()).thenReturn(true);
        when(stored.seqNo()).thenReturn(0L);
        when(stored.primaryTerm()).thenReturn(1L);
        when(stored.source()).thenReturn(Map.of("status", "published", "visibility", "public", "title", "标题"));
        doReturn(stored).when(client).get(any(Function.class), eq(Map.class));
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(1.0f)), 1, "model:1"));
        when(client.update(any(Function.class), eq(Void.class))).thenThrow(new IOException("ES write failed"), new IOException("ES write failed"), new IOException("ES write failed")).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> listener.onMessage(new ContentVectorMessage(1L)));
        assertDoesNotThrow(() -> listener.onMessage(new ContentVectorMessage(1L)));
        verify(client, times(4)).update(any(Function.class), eq(Void.class));
    }

    @Test void rejectedManualRebuildDoesNotRemainRunning() throws Exception {
        var rebuildExecutor = new VectorConfig().vectorRebuildExecutor();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            rebuildExecutor.execute(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var rebuild = new ContentVectorService(llm, client, redis, properties, rebuildExecutor, guard, builder);
            assertFalse(rebuild.startRebuild());
            assertFalse(rebuild.isRebuildRunning());
            assertFalse(rebuild.startRebuild());
            assertFalse(rebuild.isRebuildRunning());
        } finally {
            release.countDown();
            rebuildExecutor.shutdown();
        }
    }

    @Test void failedQueryItemFallsBackToKeywordSearch() {
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenReturn(new LlmPort.EmbeddingsResponse(
                List.of(new LlmPort.EmbeddingItem(0, null, "invalid input")), 2, "model:2", null));
        assertNull(service.embedQuery("keyword"));
    }

    @Test void knnFiltersPublicationVisibilityCategoryAndModelVersion() throws Exception {
        when(client.search(any(SearchRequest.class), eq(Void.class))).thenReturn(emptySearch());
        assertEquals(List.of(), service.knnSearch(new ContentVectorService.QueryVector(List.of(1.0f), "model:1"), "tech", 5));
        var captured = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captured.capture(), eq(Void.class));
        var request = captured.getValue();
        var terms = request.knn().get(0).filter().get(0).bool().must().stream()
                .collect(Collectors.toMap(query -> query.term().field(), query -> query.term().value().stringValue()));
        assertEquals(Map.of("status", "published", "visibility", "public", "category", "tech", "vectorVersion", "model:1"), terms);
    }

    @Test void rebuildUsesStoredSummaryAndSelectsOnlyPublishedPublicDocuments() throws Exception {
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(1.0f)), 1, "model:1"));
        SearchResponse<Void> batch = SearchResponse.of(s -> s.took(0).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(hit -> hit.index("content").id("1"))));
        when(client.search(any(Function.class), eq(Void.class))).thenReturn(batch, emptySearch());
        GetResponse<Map> stored = mock(GetResponse.class);
        when(stored.found()).thenReturn(true);
        when(stored.seqNo()).thenReturn(0L);
        when(stored.primaryTerm()).thenReturn(1L);
        when(stored.source()).thenReturn(Map.of("status", "published", "visibility", "public", "title", "标题", "description", "简介", "summary", "摘要"));
        when(client.get(any(Function.class), eq(Map.class))).thenReturn(stored);

        when(client.mget(any(Function.class), eq(Map.class))).thenReturn(co.elastic.clients.elasticsearch.core.MgetResponse.of(response ->
                response.docs(item -> item.result(result -> result.index("content").id("1").found(true)
                        .source(Map.of("status", "published", "visibility", "public", "title", "标题", "description", "简介", "summary", "摘要"))
                        .seqNo(0L).primaryTerm(1L)))));
        assertTrue(service.startRebuild());
        runQueuedTask();
        assertFalse(service.isRebuildRunning());
        var input = ArgumentCaptor.forClass(LlmPort.EmbeddingsRequest.class);
        verify(llm, times(2)).embeddings(input.capture(), any(feign.Request.Options.class));
        assertEquals(List.of("标题\n简介\n摘要"), input.getAllValues().get(1).getTexts());
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> requests = ArgumentCaptor.forClass(Function.class);
        verify(client, times(2)).search(requests.capture(), eq(Void.class));
        var query = requests.getAllValues().get(0).apply(new SearchRequest.Builder()).build().query().bool();
        assertEquals(Map.of("status", "published", "visibility", "public"), query.filter().stream()
                .collect(Collectors.toMap(term -> term.term().field(), term -> term.term().value().stringValue())));
        assertTrue(service.rebuildReport().scanCompleted());
        assertEquals(1, service.rebuildCursor());
        assertEquals(1, service.rebuildReport().counts().succeeded());
    }

    private void runQueuedTask() {
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
    }

    @Test void registeredFailureAdvancesCursorButReportDoesNotClaimSuccess() throws Exception {
        failedInferenceRebuild();
        assertTrue(service.startRebuild()); runQueuedTask();
        assertTrue(service.rebuildReport().scanCompleted());
        assertEquals(1, service.rebuildCursor());
        assertEquals(new ContentVectorBuilder.Counts(0, 0, 0, 1), service.rebuildReport().counts());
        verify(publisher).publishRecovery(eq(1L), eq("model:1"), contains("bad input"));
    }

    @Test void unconfirmedRecoveryKeepsPreviousCheckpointAndReportsInterruption() throws Exception {
        failedInferenceRebuild();
        doThrow(new IllegalStateException("delivery unconfirmed")).when(publisher).publishRecovery(anyLong(), anyString(), anyString());
        assertTrue(service.startRebuild()); runQueuedTask();
        assertFalse(service.rebuildReport().scanCompleted());
        assertEquals(0, service.rebuildCursor());
        assertEquals(0, service.rebuildReport().counts().pendingRecovery());
        assertTrue(service.rebuildReport().error().contains("delivery unconfirmed"));
    }

    private void failedInferenceRebuild() throws Exception {
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenAnswer(call -> {
            LlmPort.EmbeddingsRequest request = call.getArgument(0);
            if (request.getTexts().equals(List.of("向量空间版本探测"))) {
                return new LlmPort.EmbeddingsResponse(List.of(List.of(1f)), 1, "model:1");
            }
            return new LlmPort.EmbeddingsResponse(List.of(new LlmPort.EmbeddingItem(0, null, "bad input")), 1, "model:1", null);
        });
        SearchResponse<Void> batch = SearchResponse.of(response -> response.took(0).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(hit -> hit.index("content").id("1"))));
        when(client.search(any(Function.class), eq(Void.class))).thenReturn(batch, emptySearch());
        when(client.mget(any(Function.class), eq(Map.class))).thenReturn(co.elastic.clients.elasticsearch.core.MgetResponse.<Map>of(response ->
                response.docs(item -> item.result(result -> result.index("content").id("1").found(true)
                        .source(Map.of("status", "published", "visibility", "public", "title", "bad input"))
                        .seqNo(0L).primaryTerm(1L)))));
    }

    private void storedContent(Map<String, Object> content) throws Exception {
        GetResponse<Map> stored = mock(GetResponse.class);
        when(stored.found()).thenReturn(true);
        when(stored.seqNo()).thenReturn(0L);
        when(stored.primaryTerm()).thenReturn(1L);
        when(stored.source()).thenReturn(content);
        when(client.get(any(Function.class), eq(Map.class))).thenReturn(stored);
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

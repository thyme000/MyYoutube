package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.ObjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentVectorBuilderTest {
    final LlmPort llm = mock(LlmPort.class);
    final ElasticsearchClient es = mock(ElasticsearchClient.class);
    final ContentVectorPublisher publisher = mock(ContentVectorPublisher.class);
    final IdempotentGuard guard = mock(IdempotentGuard.class);
    final IdempotentGuard.Lease lease = mock(IdempotentGuard.Lease.class);
    final ContentVectorBuilder builder = new ContentVectorBuilder(llm, es, publisher, guard, new VectorProperties());
    final Map<Long, Map<String, Object>> documents = new HashMap<>();
    final Map<Long, Long> sequences = new HashMap<>();
    final List<Long> writes = new ArrayList<>();
    final Map<Long, Integer> failedWrites = new HashMap<>();
    final List<List<String>> bulkRequests = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        when(lease.acquired()).thenReturn(true);
        when(guard.acquireAll(anyList(), anyLong())).thenReturn(lease);
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenAnswer(call -> vectors(call.getArgument(0)));
        when(es.get(any(Function.class), eq(Map.class))).thenAnswer(call -> {
            Function<GetRequest.Builder, ObjectBuilder<GetRequest>> factory = call.getArgument(0);
            Long id = Long.valueOf(factory.apply(new GetRequest.Builder()).build().id());
            return GetResponse.<Map>of(result -> result.index("content").id(id.toString()).found(documents.containsKey(id))
                    .source(documents.get(id)).seqNo(sequences.getOrDefault(id, 0L)).primaryTerm(1L));
        });
        when(es.mget(any(Function.class), eq(Map.class))).thenAnswer(call -> {
            Function<MgetRequest.Builder, ObjectBuilder<MgetRequest>> factory = call.getArgument(0);
            return MgetResponse.<Map>of(result -> result.docs(factory.apply(new MgetRequest.Builder()).build().ids().stream()
                    .map(value -> {
                        Long id = Long.valueOf(value);
                        return new MultiGetResponseItem.Builder<Map>().result(item -> item.index("content").id(value)
                                .found(documents.containsKey(id)).source(documents.get(id))
                                .seqNo(sequences.getOrDefault(id, 0L)).primaryTerm(1L)).build();
                    }).toList()));
        });
        when(es.bulk(any(BulkRequest.class))).thenAnswer(call -> {
            BulkRequest request = call.getArgument(0);
            bulkRequests.add(request.operations().stream().map(operation -> operation.update().id()).toList());
            List<BulkResponseItem> results = new ArrayList<>();
            for (var operation : request.operations()) {
                var update = operation.update();
                Long id = Long.valueOf(update.id());
                writes.add(id);
                var result = new BulkResponseItem.Builder().operationType(OperationType.Update).index("content").id(update.id());
                if (failedWrites.getOrDefault(id, 0) > 0) {
                    failedWrites.compute(id, (key, remaining) -> remaining - 1);
                    results.add(result.status(429).error(error -> error.type("busy").reason("ES busy")).build());
                } else {
                    assertEquals(sequences.getOrDefault(id, 0L), update.ifSeqNo());
                    assertEquals(1L, update.ifPrimaryTerm());
                    documents.get(id).putAll(((JsonData) update.action().doc()).to(Map.class));
                    sequences.merge(id, 1L, Long::sum);
                    results.add(result.status(200).build());
                }
            }
            return BulkResponse.of(response -> response.took(1).errors(results.stream().anyMatch(item -> item.error() != null)).items(results));
        });
        doAnswer(call -> {
            UpdateRequest<Void, JsonData> request = update(call);
            Long id = Long.valueOf(request.id());
            writes.add(id);
            if (failedWrites.getOrDefault(id, 0) > 0) {
                failedWrites.compute(id, (key, remaining) -> remaining - 1);
                throw new IOException("ES write unavailable");
            }
            assertEquals(sequences.getOrDefault(id, 0L), request.ifSeqNo());
            assertEquals(1L, request.ifPrimaryTerm());
            documents.get(id).putAll(request.doc().to(Map.class));
            sequences.merge(id, 1L, Long::sum);
            return null;
        }).when(es).update(any(Function.class), eq(Void.class));
    }

    @Test void filtersInvalidItemsAndPreservesIdToVectorMapping() {
        content(1, " "); content(2, "private").put("visibility", "private");
        content(3, "banned").put("status", "banned"); content(4, "done").put("vectorVersion", "model:2");
        content(6, "valid");
        assertEquals(new ContentVectorBuilder.Counts(1, 1, 4, 0), builder.batch(List.of(1L, 2L, 3L, 4L, 5L, 6L), "model:2"));
        assertEquals(List.of(6L), writes);
        var request = ArgumentCaptor.forClass(LlmPort.EmbeddingsRequest.class);
        verify(llm).embeddings(request.capture(), any(feign.Request.Options.class));
        assertEquals(List.of("valid"), request.getValue().getTexts());
        verify(guard).acquireAll(List.of("{content-vector}:1", "{content-vector}:2", "{content-vector}:3",
                "{content-vector}:4", "{content-vector}:5", "{content-vector}:6"), 30);
    }

    @Test void allEmptyOrMissingDoesNotInvokeModel() {
        content(1, " ");
        assertEquals(new ContentVectorBuilder.Counts(0, 0, 2, 0), builder.batch(List.of(1L, 2L), "model:2"));
        verifyNoInteractions(llm, publisher);
        verify(lease).complete(IdempotentGuard.DONE, 0);
    }

    @Test void partialInferenceWritesGoodItemsAndRegistersOnlyFailedItemsWithoutRecomputing() {
        content(1, "good"); content(2, "bad");
        doReturn(new LlmPort.EmbeddingsResponse(List.of(new LlmPort.EmbeddingItem(0, List.of(1f, 2f), null),
                new LlmPort.EmbeddingItem(1, null, "bad text")), 2, "model:2", null)).when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertEquals(new ContentVectorBuilder.Counts(1, 0, 0, 1), builder.batch(List.of(1L, 2L), "model:2"));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
        verify(publisher).publishRecovery(eq(2L), eq("model:2"), contains("bad text"));
        assertEquals(List.of(1L), writes);
    }

    @Test void knownModelOutageAbortsWithoutFiftyIndividualCalls() {
        content(1, "good"); content(2, "good");
        doThrow(new ModelUnavailable("LLM", "MODEL_CIRCUIT_OPEN", 30, null)).when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertThrows(ModelUnavailable.class, () -> builder.batch(List.of(1L, 2L), "model:2"));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
        verifyNoInteractions(publisher);
        assertTrue(writes.isEmpty());
    }

    @Test void modelVersionChangeAbortsWithoutWritingOldVersionStamp() {
        content(1, "good");
        doReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(1f, 2f)), 2, "model:3")).when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertThrows(IllegalStateException.class, () -> builder.batch(List.of(1L), "model:2"));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
        verifyNoInteractions(publisher);
        assertTrue(writes.isEmpty());
    }

    @Test void writeRetryReusesModelResultAndDoesNotRewriteEarlierItems() throws Exception {
        content(1, "first"); content(2, "second"); content(3, "third"); failedWrites.put(2L, 1);
        assertEquals(new ContentVectorBuilder.Counts(3, 0, 0, 0), builder.batch(List.of(1L, 2L, 3L), "model:2"));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
        assertEquals(List.of(1L, 2L, 3L, 2L), writes);
        assertEquals(List.of(List.of("1", "2", "3"), List.of("2")), bulkRequests);
        verify(es, never()).update(any(Function.class), eq(Void.class));
        verifyNoInteractions(publisher);
    }

    @Test void exhaustedWriteRegistersRecoveryAndStillProcessesOtherItems() {
        content(1, "bad write"); content(2, "good write"); failedWrites.put(1L, 3);
        assertEquals(new ContentVectorBuilder.Counts(1, 0, 0, 1), builder.batch(List.of(1L, 2L), "model:2"));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
        assertEquals(List.of(1L, 2L, 1L, 1L), writes);
        verify(publisher).publishRecovery(eq(1L), eq("model:2"), anyString());
    }

    @Test void unconfirmedRecoveryDeliveryFailsTheBatch() {
        content(1, "bad write"); failedWrites.put(1L, 3);
        doThrow(new IllegalStateException("send unconfirmed")).when(publisher).publishRecovery(anyLong(), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> builder.batch(List.of(1L), "model:2"));
        verify(lease, never()).complete(anyString(), anyLong());
        verify(lease).close();
    }

    @Test void mgetItemFailureIsNotClassifiedAsMissingContent() throws Exception {
        doReturn(MgetResponse.<Map>of(result -> result.docs(item -> item.failure(failure -> failure.index("content").id("1")
                .error(error -> error.type("unavailable").reason("offline")))))).when(es).mget(any(Function.class), eq(Map.class));
        assertThrows(IllegalStateException.class, () -> builder.batch(List.of(1L), "model:2"));
        verifyNoInteractions(llm, publisher);
    }

    @Test void concurrentWinnerIsRecognizedWithoutOverwritingIt() throws Exception {
        content(1, "same text");
        doAnswer(call -> {
            documents.get(1L).put("vectorVersion", "model:2");
            documents.get(1L).put("vector", List.of(9f, 9f));
            sequences.put(1L, 1L);
            return bulkConflict();
        }).when(es).bulk(any(BulkRequest.class));
        assertEquals(new ContentVectorBuilder.Counts(0, 1, 0, 0), builder.batch(List.of(1L), "model:2"));
        assertEquals(List.of(9f, 9f), documents.get(1L).get("vector"));
        verify(es).bulk(any(BulkRequest.class));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
    }

    @Test void visibilityChangeDuringWriteRetryIsSkippedWithoutOverwritingMetadata() throws Exception {
        content(1, "same text");
        doAnswer(call -> {
            documents.get(1L).put("visibility", "private"); sequences.put(1L, 1L);
            return bulkConflict();
        }).when(es).bulk(any(BulkRequest.class));
        assertEquals(new ContentVectorBuilder.Counts(0, 0, 1, 0), builder.batch(List.of(1L), "model:2"));
        assertFalse(documents.get(1L).containsKey("vector"));
        verifyNoInteractions(publisher);
    }

    @Test void changedTextDoesNotReceiveTheOldComputedVector() throws Exception {
        content(1, "old text");
        doAnswer(call -> {
            documents.get(1L).put("title", "new text"); sequences.put(1L, 1L);
            return bulkConflict();
        }).when(es).bulk(any(BulkRequest.class));
        assertEquals(new ContentVectorBuilder.Counts(0, 0, 0, 1), builder.batch(List.of(1L), "model:2"));
        assertFalse(documents.get(1L).containsKey("vector"));
        verify(publisher).publishRecovery(eq(1L), eq("model:2"), contains("已变化"));
    }

    @Test void targetedRecoveryRebuildsOldVersionButNormalDeliveryReusesIt() {
        content(1, "old").put("vectorVersion", "model:1");
        builder.single(1L, null, lease);
        verifyNoInteractions(llm);
        builder.single(1L, "model:2", lease);
        assertEquals("model:2", documents.get(1L).get("vectorVersion"));
        builder.single(1L, "model:2", lease);
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
    }

    @Test void malformedVectorsAreNeverWritten() {
        content(1, "good");
        doReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(Float.NaN, 1f)), 2, "model:2")).when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertEquals(new ContentVectorBuilder.Counts(0, 0, 0, 1), builder.batch(List.of(1L), "model:2"));
        assertTrue(writes.isEmpty());
        verify(publisher).publishRecovery(eq(1L), eq("model:2"), anyString());
    }

    @Test void fiftySuccessfulItemsUseOneBulkRequest() throws Exception {
        var ids = java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList();
        ids.forEach(id -> content(id, "text " + id));
        assertEquals(new ContentVectorBuilder.Counts(50, 0, 0, 0), builder.batch(ids, "model:2"));
        assertEquals(1, bulkRequests.size());
        assertEquals(50, bulkRequests.get(0).size());
        verify(es).bulk(any(BulkRequest.class));
        verify(es, never()).update(any(Function.class), eq(Void.class));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
    }

    @Test void unknownBulkOutcomeRereadsAndOnlyRetriesUnconfirmedItems() throws Exception {
        content(1, "first"); content(2, "second");
        var original = mockingDetails(es).getStubbings().stream()
                .filter(stub -> stub.getInvocation().getMethod().getName().equals("bulk")).findFirst().orElseThrow();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (calls.getAndIncrement() == 0) {
                documents.get(1L).put("vectorVersion", "model:2");
                documents.get(1L).put("vector", List.of(1f, 2f));
                throw new IOException("response lost after first item committed");
            }
            return original.answer(call);
        }).when(es).bulk(any(BulkRequest.class));
        assertEquals(new ContentVectorBuilder.Counts(1, 1, 0, 0), builder.batch(List.of(1L, 2L), "model:2"));
        assertEquals(List.of(List.of("2")), bulkRequests);
        verify(es, times(2)).bulk(any(BulkRequest.class));
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
    }

    @Test void definitiveMappingFailureIsRegisteredWithoutRetryingOtherItems() throws Exception {
        content(1, "bad mapping");
        doReturn(BulkResponse.of(response -> response.took(1).errors(true).items(item -> item
                .operationType(OperationType.Update).index("content").id("1").status(400)
                .error(error -> error.type("mapper_parsing_exception").reason("bad vector")))))
                .when(es).bulk(any(BulkRequest.class));
        assertEquals(new ContentVectorBuilder.Counts(0, 0, 0, 1), builder.batch(List.of(1L), "model:2"));
        verify(es).bulk(any(BulkRequest.class));
        verify(publisher).publishRecovery(eq(1L), eq("model:2"), contains("status=400"));
    }

    @Test void malformedVectorDoesNotDiscardItsValidNeighbor() {
        content(1, "good"); content(2, "bad");
        doReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(1f, 2f), List.of(Float.NaN, 1f)), 2, "model:2"))
                .when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertEquals(new ContentVectorBuilder.Counts(1, 0, 0, 1), builder.batch(List.of(1L, 2L), "model:2"));
        assertEquals(List.of(1L), writes);
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
    }

    @Test void indexedResponseCanBeReorderedWithoutCrossingVectors() {
        content(1, "first"); content(2, "second");
        doReturn(new LlmPort.EmbeddingsResponse(List.of(new LlmPort.EmbeddingItem(1, List.of(3f, 4f), null),
                new LlmPort.EmbeddingItem(0, List.of(1f, 2f), null)), 2, "model:2", null)).when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertEquals(new ContentVectorBuilder.Counts(2, 0, 0, 0), builder.batch(List.of(1L, 2L), "model:2"));
        assertEquals(List.of(1f, 2f), documents.get(1L).get("vector"));
        assertEquals(List.of(3f, 4f), documents.get(2L).get("vector"));
    }

    @Test void partialResultsAreWrittenBeforeKnownOutageStopsTheScan() {
        content(1, "good"); content(2, "not attempted");
        doReturn(new LlmPort.EmbeddingsResponse(List.of(new LlmPort.EmbeddingItem(0, List.of(1f, 2f), null),
                new LlmPort.EmbeddingItem(1, null, "unavailable")), 2, "model:2", "upstream unavailable"))
                .when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertThrows(ModelUnavailable.class, () -> builder.batch(List.of(1L, 2L), "model:2"));
        assertEquals(List.of(1L), writes);
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
        verifyNoInteractions(publisher);
        verify(lease, never()).complete(anyString(), anyLong());
    }

    @Test void duplicateInputIndexesAbortWithoutGuessingOrRecomputing() {
        content(1, "first"); content(2, "second");
        doReturn(new LlmPort.EmbeddingsResponse(List.of(new LlmPort.EmbeddingItem(0, List.of(1f, 2f), null),
                new LlmPort.EmbeddingItem(0, List.of(3f, 4f), null)), 2, "model:2", null)).when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertThrows(IllegalStateException.class, () -> builder.batch(List.of(1L, 2L), "model:2"));
        assertTrue(writes.isEmpty());
        verify(llm).embeddings(any(), any(feign.Request.Options.class));
        verifyNoInteractions(publisher);
    }

    private Map<String, Object> content(long id, String title) {
        var source = new HashMap<String, Object>(Map.of("status", "published", "visibility", "public", "title", title));
        documents.put(id, source);
        return source;
    }

    private LlmPort.EmbeddingsResponse vectors(LlmPort.EmbeddingsRequest request) {
        return new LlmPort.EmbeddingsResponse(request.getTexts().stream().map(text -> List.of(1f, 2f)).toList(), 2, "model:2");
    }

    private UpdateRequest<Void, JsonData> update(org.mockito.invocation.InvocationOnMock call) {
        Function<UpdateRequest.Builder<Void, JsonData>, ObjectBuilder<UpdateRequest<Void, JsonData>>> factory = call.getArgument(0);
        return factory.apply(new UpdateRequest.Builder<>()).build();
    }

    private BulkResponse bulkConflict() {
        return BulkResponse.of(response -> response.took(1).errors(true).items(item -> item
                .operationType(OperationType.Update).index("content").id("1").status(409)
                .error(error -> error.type("version_conflict_engine_exception").reason("changed"))));
    }
}

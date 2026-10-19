package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.idempotent.IdempotentAspect;
import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.json.JsonData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "idempotent.redis.port", matches = "[0-9]+")
class LiveContentVectorIdempotencyTest {
    final long firstId = System.nanoTime();
    final long secondId = firstId + 1;
    final LlmPort llm = mock(LlmPort.class);
    final ElasticsearchClient es = mock(ElasticsearchClient.class);
    final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    final Map<String, Map<String, Object>> documents = new ConcurrentHashMap<>();
    final AtomicReference<Runnable> rebuild = new AtomicReference<>();
    final AtomicInteger inferenceCalls = new AtomicInteger();
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    ThreadPoolTaskScheduler scheduler;
    IdempotentGuard guard;
    ContentVectorService vectors;

    @BeforeEach void setup() throws Exception {
        connection = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("idempotent.redis.port"));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        guard = new IdempotentGuard(redis, scheduler);
        vectors = proxy(guard);
        doAnswer(call -> { rebuild.set(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        documents.put(String.valueOf(firstId), Map.of("title", "first", "status", "published", "visibility", "public"));
        documents.put(String.valueOf(secondId), Map.of("title", "second", "status", "published", "visibility", "public"));
        when(es.get(any(Function.class), eq(Map.class))).thenAnswer(call -> {
            Function<GetRequest.Builder, ?> builder = call.getArgument(0);
            GetRequest request = (GetRequest) ((co.elastic.clients.util.ObjectBuilder<?>) builder.apply(new GetRequest.Builder())).build();
            Map<String, Object> content = documents.get(request.id());
            return GetResponse.of(response -> response.index("content").id(request.id()).found(content != null).source(content).seqNo(0L).primaryTerm(1L));
        });
        when(es.update(any(Function.class), eq(Void.class))).thenAnswer(call -> {
            Function<UpdateRequest.Builder<Void, JsonData>, ?> builder = call.getArgument(0);
            UpdateRequest<Void, JsonData> request = (UpdateRequest<Void, JsonData>)
                    ((co.elastic.clients.util.ObjectBuilder<?>) builder.apply(new UpdateRequest.Builder<Void, JsonData>())).build();
            documents.compute(request.id(), (id, content) -> {
                var updated = new HashMap<>(content);
                updated.putAll(request.doc().to(Map.class));
                return updated;
            });
            return null;
        });
        when(es.bulk(any(BulkRequest.class))).thenAnswer(call -> {
            BulkRequest request = call.getArgument(0);
            for (var operation : request.operations()) {
                var update = operation.update();
                documents.compute(update.id(), (id, content) -> {
                    var updated = new HashMap<>(content);
                    updated.putAll(((JsonData) update.action().doc()).to(Map.class));
                    return updated;
                });
            }
            return BulkResponse.of(response -> response.took(1).errors(false)
                    .items(request.operations().stream().map(operation -> BulkResponseItem.of(
                            item -> item.operationType(OperationType.Update)
                                    .index("content").id(operation.update().id()).status(200))).toList()));
        });
        when(es.mget(any(Function.class), eq(Map.class))).thenAnswer(call -> {
            Function<co.elastic.clients.elasticsearch.core.MgetRequest.Builder, ?> builder = call.getArgument(0);
            var request = (co.elastic.clients.elasticsearch.core.MgetRequest) ((co.elastic.clients.util.ObjectBuilder<?>) builder.apply(new co.elastic.clients.elasticsearch.core.MgetRequest.Builder())).build();
            return co.elastic.clients.elasticsearch.core.MgetResponse.<Map>of(response -> response.docs(request.ids().stream().map(id ->
                    new co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem.Builder<Map>().result(result ->
                            result.index("content").id(id).found(documents.containsKey(id)).source(documents.get(id)).seqNo(0L).primaryTerm(1L)).build()).toList()));
        });
        when(llm.embeddings(any(), any(feign.Request.Options.class))).thenAnswer(call -> response(call.getArgument(0)));
        when(es.search(any(Function.class), eq(Void.class))).thenReturn(batch(), empty());
    }

    @AfterEach void cleanup() {
        scheduler.shutdown();
        redis.delete(List.of(contentKey(firstId), contentKey(secondId), "idempotent:{content-vector}:rebuild",
                "search:{content-vector}:rebuild"));
        connection.destroy();
    }

    @Test void simultaneousSingleConsumersRetryThenReuseEsCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        blockInference(entered, release);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> vectors.embedContent(firstId));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var other = proxy(new IdempotentGuard(redis, scheduler));
            assertThrows(BizException.class, () -> other.embedContent(firstId));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            other.embedContent(firstId);
            assertEquals(1, inferenceCalls.get());
            assertEquals("model:1", documents.get(String.valueOf(firstId)).get("vectorVersion"));
            assertNull(redis.opsForValue().get(contentKey(firstId)));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void batchOwnsEachContentUntilItsWriteCompletes() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        blockInference(entered, release);
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(vectors.startRebuild());
            var task = pool.submit(rebuild.get());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(BizException.class, () -> vectors.embedContent(firstId));
            assertThrows(BizException.class, () -> vectors.embedContent(secondId));
            release.countDown();
            task.get(5, TimeUnit.SECONDS);
            vectors.embedContent(firstId);
            vectors.embedContent(secondId);
            assertEquals(1, inferenceCalls.get());
            assertFalse(vectors.isRebuildRunning());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void batchConflictReleasesEarlierClaimsAndCanResumeAfterSingleCompletion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        blockInference(entered, release);
        when(es.search(any(Function.class), eq(Void.class))).thenReturn(batch(), batch(), empty());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var single = pool.submit(() -> vectors.embedContent(secondId));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(vectors.startRebuild());
            rebuild.get().run();
            assertFalse(vectors.isRebuildRunning());
            assertNull(redis.opsForValue().get(contentKey(firstId)));
            assertEquals(0, vectors.rebuildCursor());
            assertNull(redis.opsForValue().get("idempotent:{content-vector}:rebuild"));
            release.countDown();
            single.get(5, TimeUnit.SECONDS);
            assertTrue(vectors.startRebuild());
            rebuild.get().run();
            assertEquals(2, inferenceCalls.get());
            assertEquals("model:1", documents.get(String.valueOf(firstId)).get("vectorVersion"));
            assertEquals("model:1", documents.get(String.valueOf(secondId)).get("vectorVersion"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void lostBatchLeaseDoesNotWriteOrAdvanceAndPreservesNewOwner() throws Exception {
        doAnswer(call -> {
            LlmPort.EmbeddingsRequest request = call.getArgument(0);
            if (!isProbe(request)) redis.opsForValue().set(contentKey(firstId), "RUNNING:replacement", Duration.ofSeconds(10));
            return response(request);
        }).when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertTrue(vectors.startRebuild());
        rebuild.get().run();
        assertEquals(1, inferenceCalls.get());
        verify(es, never()).update(any(Function.class), eq(Void.class));
        verify(es, never()).bulk(any(BulkRequest.class));
        assertEquals(0, vectors.rebuildCursor());
        assertEquals("RUNNING:replacement", redis.opsForValue().get(contentKey(firstId)));
        assertNull(redis.opsForValue().get(contentKey(secondId)));
    }

    @Test void globalRebuildOccupancyCoversBackgroundExecution() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        blockInference(entered, release);
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(vectors.startRebuild());
            var first = pool.submit(rebuild.get());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var other = proxy(new IdempotentGuard(redis, scheduler));
            assertTrue(other.startRebuild());
            rebuild.get().run();
            assertFalse(other.isRebuildRunning());
            verify(es, times(1)).search(any(Function.class), eq(Void.class));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertNull(redis.opsForValue().get("idempotent:{content-vector}:rebuild"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void crashBeforeCheckpointCanResumeWithoutRecomputingWrittenVectors() throws Exception {
        var template = spy(redis);
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            Object[] args = (Object[]) call.getRawArguments()[2];
            var report = cn.diaojiahong.myyoutube.common.utils.JsonUtil.fromJson(args[1].toString(), ContentVectorService.RebuildReport.class);
            if (report.cursor() > 0 && failOnce.getAndSet(false)) throw new IllegalStateException("checkpoint unavailable");
            return call.callRealMethod();
        }).when(template).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class));
        var interrupted = new ContentVectorService(llm, es, template, new VectorProperties(), executor, guard,
                new ContentVectorBuilder(llm, es, mock(ContentVectorPublisher.class), guard, new VectorProperties()));
        when(es.search(any(Function.class), eq(Void.class))).thenReturn(batch(), batch(), empty());
        assertTrue(interrupted.startRebuild()); rebuild.get().run();
        assertEquals(0, interrupted.rebuildCursor());
        assertNotNull(interrupted.rebuildReport().error());
        assertEquals(1, inferenceCalls.get());
        assertTrue(vectors.startRebuild()); rebuild.get().run();
        assertTrue(vectors.rebuildReport().scanCompleted());
        assertEquals(2, vectors.rebuildReport().counts().existing());
        assertEquals(1, inferenceCalls.get());
    }

    @Test void newModelVersionResetsPreviousCursorAndCounts() throws Exception {
        var old = new ContentVectorService.RebuildReport("old:model", secondId,
                new ContentVectorBuilder.Counts(20, 0, 0, 0), false, null);
        redis.opsForValue().set("search:{content-vector}:rebuild", cn.diaojiahong.myyoutube.common.utils.JsonUtil.toJson(old));
        assertTrue(vectors.startRebuild()); rebuild.get().run();
        assertEquals("model:1", vectors.rebuildReport().targetVersion());
        assertEquals(2, vectors.rebuildReport().counts().succeeded());
        assertTrue(vectors.rebuildReport().scanCompleted());
        var requests = org.mockito.ArgumentCaptor.forClass(Function.class);
        verify(es, times(2)).search(requests.capture(), eq(Void.class));
        var first = (co.elastic.clients.elasticsearch.core.SearchRequest) ((co.elastic.clients.util.ObjectBuilder<?>) requests.getAllValues()
                .get(0).apply(new co.elastic.clients.elasticsearch.core.SearchRequest.Builder())).build();
        assertTrue(first.searchAfter().isEmpty());
    }

    @Test void lostGlobalOwnerCannotAdvanceCheckpointOrDeleteNewOwner() {
        doAnswer(call -> {
            LlmPort.EmbeddingsRequest request = call.getArgument(0);
            if (!isProbe(request)) redis.opsForValue().set("idempotent:{content-vector}:rebuild", "RUNNING:replacement", Duration.ofSeconds(10));
            return response(request);
        }).when(llm).embeddings(any(), any(feign.Request.Options.class));
        assertTrue(vectors.startRebuild()); rebuild.get().run();
        assertEquals(0, vectors.rebuildCursor());
        assertEquals("RUNNING:replacement", redis.opsForValue().get("idempotent:{content-vector}:rebuild"));
        assertFalse(vectors.rebuildReport().scanCompleted());
    }

    private ContentVectorService proxy(IdempotentGuard instanceGuard) {
        var target = new ContentVectorService(llm, es, redis, new VectorProperties(), executor, instanceGuard, new ContentVectorBuilder(llm, es, mock(ContentVectorPublisher.class), instanceGuard, new VectorProperties()));
        var factory = new AspectJProxyFactory(target);
        factory.addAspect(new IdempotentAspect(instanceGuard));
        return factory.getProxy();
    }

    private void blockInference(CountDownLatch entered, CountDownLatch release) {
        doAnswer(call -> {
            LlmPort.EmbeddingsRequest request = call.getArgument(0);
            if (!isProbe(request)) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return response(request);
        }).when(llm).embeddings(any(), any(feign.Request.Options.class));
    }

    private LlmPort.EmbeddingsResponse response(LlmPort.EmbeddingsRequest request) {
        if (!isProbe(request)) inferenceCalls.incrementAndGet();
        return new LlmPort.EmbeddingsResponse(request.getTexts().stream().map(text -> List.of(1.0f)).toList(), 1, "model:1");
    }

    private boolean isProbe(LlmPort.EmbeddingsRequest request) {
        return request.getTexts().equals(List.of("向量空间版本探测"));
    }

    private String contentKey(long id) { return RedisConstants.IDEMPOTENT + "{content-vector}:" + id; }

    private SearchResponse<Void> batch() {
        return SearchResponse.of(response -> response.took(0).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(hit -> hit.index("content").id(String.valueOf(firstId)))
                        .hits(hit -> hit.index("content").id(String.valueOf(secondId)))));
    }

    private SearchResponse<Void> empty() {
        return SearchResponse.of(response -> response.took(0).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0)).hits(hits -> hits.hits(List.of())));
    }
}

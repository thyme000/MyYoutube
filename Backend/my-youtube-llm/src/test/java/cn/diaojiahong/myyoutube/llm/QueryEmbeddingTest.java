package cn.diaojiahong.myyoutube.llm;

import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import cn.diaojiahong.myyoutube.llm.embedding.EmbeddingProvider;
import cn.diaojiahong.myyoutube.llm.embedding.OpenAiCompatEmbeddingProvider;
import cn.diaojiahong.myyoutube.llm.embedding.QueryEmbeddingService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Timeout(10)
class QueryEmbeddingTest {
    @Test void metadataReturnsCurrentVersionWithoutStartingModels() {
        var properties = new LlmProperties(); properties.getEmbedding().setModel("model");
        properties.getEmbedding().setDims(2);
        var provider = mock(EmbeddingProvider.class);
        var service = new QueryEmbeddingService(provider, properties);
        assertEquals(new QueryEmbeddingPort.Info("model:2", 2), service.info());
        properties.getEmbedding().setVersion("new-version");
        assertEquals(new QueryEmbeddingPort.Info("new-version", 2), service.info());
        verifyNoInteractions(provider);
    }

    @Test void expiredRequestsDoNotStartModels() {
        var provider = mock(EmbeddingProvider.class);
        var service = new QueryEmbeddingService(provider, new LlmProperties());
        assertThrows(ModelUnavailable.class, () -> service.embed(new QueryEmbeddingPort.Query("query", 1)));
        verifyNoInteractions(provider);
    }

    @Test void queryConcurrencyRejectsQuicklyWithoutChangingContentRules() throws Exception {
        var properties = new LlmProperties(); properties.getEmbedding().setQueryMaxConcurrent(1);
        properties.getEmbedding().setDims(2);
        var provider = mock(EmbeddingProvider.class);
        var service = new QueryEmbeddingService(provider, properties);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(provider.embedQuery(anyString(), any())).thenAnswer(call -> {
            entered.countDown(); release.await(); return List.of(new float[]{1, 2});
        });
        when(provider.embed(any())).thenReturn(List.of(new float[]{1, 2}));
        var caller = Executors.newSingleThreadExecutor();
        try {
            var first = caller.submit(() -> service.embed(query()));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(ModelUnavailable.class, () -> service.embed(query()));
            assertEquals(1, provider.embed(List.of("content")).size());
            release.countDown(); assertEquals(2, first.get(1, TimeUnit.SECONDS).getDims());
            assertEquals(2, service.embed(query()).getDims());
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test void blockedModelAndTricklingBodyAreCancelledWithoutRetryAndSlotsRecover() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool(); var calls = new AtomicInteger();
        server.setExecutor(workers);
        server.createContext("/embeddings", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try {
                while (true) { exchange.getResponseBody().write(' '); exchange.getResponseBody().flush(); Thread.sleep(20); }
            } catch (Exception ignored) { }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var properties = new LlmProperties();
            properties.getEmbedding().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getEmbedding().setModel("test");
            properties.getEmbedding().setQueryTimeoutMillis(180);
            properties.getEmbedding().setQueryMaxConcurrent(1);
            var service = new QueryEmbeddingService(new OpenAiCompatEmbeddingProvider(properties), properties);
            for (int i = 0; i < 2; i++) {
                long start = System.nanoTime();
                assertThrows(ModelUnavailable.class, () -> service.embed(query()));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 800);
            }
            assertEquals(2, calls.get());
        } finally { server.stop(0); workers.shutdownNow(); }
    }

    @Test void queryProviderReceivesRemainingBudgetClampedToModelLimit() {
        var properties = new LlmProperties(); properties.getEmbedding().setDims(1);
        properties.getEmbedding().setQueryTimeoutMillis(500);
        var provider = mock(EmbeddingProvider.class);
        when(provider.embedQuery(anyString(), any())).thenAnswer(call -> {
            Duration timeout = call.getArgument(1); assertTrue(timeout.toMillis() > 0 && timeout.toMillis() <= 100);
            return List.of(new float[]{1});
        });
        assertEquals(1, new QueryEmbeddingService(provider, properties)
                .embed(new QueryEmbeddingPort.Query("query", System.currentTimeMillis() + 100)).getDims());
        verify(provider, never()).embed(any());
    }

    @Test void queryEndpointUsesIndependentMetricsAndContract() throws Exception {
        var service = mock(QueryEmbeddingService.class);
        when(service.embed(any())).thenReturn(new cn.diaojiahong.myyoutube.llm.api.LlmPort.EmbeddingsResponse(
                List.of(List.of(1f)), 1, "test:1"));
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var endpoint = new cn.diaojiahong.myyoutube.llm.controller.QueryEmbeddingController(service, new LlmRequestMetrics(registry));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(endpoint).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/llm/query-embedding")
                .contentType("application/json").content("{\"text\":\"q\",\"deadlineEpochMillis\":123}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.version").value("test:1"));
        assertEquals(0, registry.get("llm.requests.active").tag("operation", "query-embedding").gauge().value());
        assertEquals(1, registry.get("llm.request.duration").tags("operation", "query-embedding", "outcome", "success").timer().count());
    }

    static QueryEmbeddingPort.Query query() { return new QueryEmbeddingPort.Query("query", System.currentTimeMillis() + 2000); }
}

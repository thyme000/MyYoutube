package cn.diaojiahong.myyoutube.llm;

import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.controller.InternalLlmController;
import cn.diaojiahong.myyoutube.llm.embedding.EmbeddingOrchestrator;
import cn.diaojiahong.myyoutube.llm.embedding.EmbeddingProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmRequestMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmRequestMetrics metrics = new LlmRequestMetrics(registry);
    private final LlmClient client = mock(LlmClient.class);
    private final LlmProperties properties = new LlmProperties();
    private final InternalLlmController controller = new InternalLlmController(properties, client,
            mock(EmbeddingOrchestrator.class), metrics);

    @Test
    void successfulRequestMeasuresActiveCallsAndReturnsOriginalResponse() {
        var response = new LlmPort.ChatResponse("完成", "model", "stop", null);
        when(client.chatDetailed(anyList(), any(), any(), any(), any())).thenAnswer(call -> {
            assertEquals(1, active("chat"));
            return response;
        });
        assertSame(response, controller.chat(chatRequest()));
        assertEquals(0, active("chat"));
        assertEquals(1, completed("chat", "success"));
        assertEquals(0, completed("chat", "error"));
    }

    @Test
    void failuresAndWrappedTimeoutsRemainDistinctAndCleanUpActiveCalls() {
        var error = new IllegalStateException("HTTP 503");
        var timeout = new IllegalStateException("LLM 调用失败", new HttpTimeoutException("deadline"));
        when(client.chatDetailed(anyList(), any(), any(), any(), any())).thenThrow(error, timeout);
        assertSame(error, assertThrows(IllegalStateException.class, () -> controller.chat(chatRequest())));
        assertSame(timeout, assertThrows(IllegalStateException.class, () -> controller.chat(chatRequest())));
        assertEquals(1, completed("chat", "error"));
        assertEquals(1, completed("chat", "timeout"));
        assertEquals(0, active("chat"));
    }

    @Test
    void rejectedInputDoesNotCountAsInferenceFailure() {
        assertThrows(RuntimeException.class, () -> controller.chat(new LlmPort.ChatRequest()));
        verifyNoInteractions(client);
        assertEquals(0, completed("chat", "error"));
        assertEquals(0, active("chat"));
    }

    @Test
    void embeddingRetryIsCountedOncePerLogicalRequest() {
        properties.getEmbedding().setDims(1);
        var provider = mock(EmbeddingProvider.class);
        when(provider.embed(anyList())).thenThrow(new IllegalStateException("temporary"))
                .thenReturn(List.of(new float[]{1}));
        var endpoint = new InternalLlmController(properties, client,
                new EmbeddingOrchestrator(provider, properties), metrics);
        var request = new LlmPort.EmbeddingsRequest();
        request.setTexts(List.of("text"));
        assertEquals(1, endpoint.embeddings(request).getDims());
        verify(provider, times(2)).embed(anyList());
        assertEquals(1, completed("embeddings", "success"));
        assertEquals(0, completed("embeddings", "error"));
        assertEquals(0, active("embeddings"));
    }

    @Test
    void prometheusExportsZeroOutcomesBeforeFirstRequest() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var measured = new LlmRequestMetrics(prometheus);
            measured.record("chat", () -> "ok");
            String scrape = prometheus.scrape();
            assertTrue(scrape.contains("llm_request_duration_seconds_count{operation=\"chat\",outcome=\"success\"} 1"));
            assertTrue(scrape.contains("llm_request_duration_seconds_count{operation=\"chat\",outcome=\"timeout\"} 0"));
            assertTrue(scrape.contains("llm_requests_active{operation=\"embeddings\"} 0"));
        } finally {
            prometheus.close();
        }
    }

    private LlmPort.ChatRequest chatRequest() {
        var request = new LlmPort.ChatRequest();
        request.setMessages(List.of(new LlmPort.Message("user", "hello", null)));
        return request;
    }

    private long completed(String operation, String outcome) {
        return registry.get("llm.request.duration").tags("operation", operation, "outcome", outcome).timer().count();
    }

    private double active(String operation) {
        return registry.get("llm.requests.active").tag("operation", operation).gauge().value();
    }
}

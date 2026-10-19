package cn.diaojiahong.myyoutube.llm;

import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.llm.embedding.EmbeddingOrchestrator;
import cn.diaojiahong.myyoutube.llm.embedding.EmbeddingProvider;
import cn.diaojiahong.myyoutube.llm.embedding.OpenAiCompatEmbeddingProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.IntStream;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.controller.InternalLlmController;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingFailureTest {
    @ParameterizedTest @ValueSource(ints = {429, 503})
    void upstreamCapacityOrOutageIsExplicitlyUnavailable(int status) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> { exchange.sendResponseHeaders(status, -1); exchange.close(); });
        server.start();
        try {
            var properties = new LlmProperties();
            properties.getEmbedding().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getEmbedding().setModel("test");
            assertThrows(ModelUnavailable.class, () -> new OpenAiCompatEmbeddingProvider(properties).embed(List.of("text")));
        } finally { server.stop(0); }
    }

    @Test void knownUnavailableDoesNotUseExecutionRetryBudget() {
        var provider = mock(EmbeddingProvider.class);
        when(provider.embed(anyList())).thenThrow(new ModelUnavailable("LLM", "MODEL_UPSTREAM_UNAVAILABLE", 30, null));
        assertThrows(ModelUnavailable.class, () -> new EmbeddingOrchestrator(provider, new LlmProperties()).embedAll(List.of("text")));
        verify(provider).embed(anyList());
    }

    @Test void ordinaryExecutionFailureStillUsesExistingTwoAttempts() {
        var provider = mock(EmbeddingProvider.class);
        when(provider.embed(anyList())).thenThrow(new IllegalStateException("invalid input"));
        var result = new EmbeddingOrchestrator(provider, new LlmProperties()).embedAll(List.of("text"));
        assertNull(result.items().get(0).vector());
        assertTrue(result.items().get(0).error().contains("invalid input"));
        verify(provider, times(2)).embed(anyList());
    }

    @Test void onlyTheFailedSubBatchFallsBackAndEarlierSuccessesAreNotComputedAgain() {
        var provider = mock(EmbeddingProvider.class);
        var properties = properties(16);
        List<List<String>> requests = new ArrayList<>();
        when(provider.embed(anyList())).thenAnswer(call -> {
            List<String> texts = List.copyOf(call.getArgument(0));
            requests.add(texts);
            if (texts.contains("21")) throw new IllegalStateException("poison 21");
            return texts.stream().map(text -> new float[]{Float.parseFloat(text), 1}).toList();
        });
        var result = new EmbeddingOrchestrator(provider, properties)
                .embedAll(IntStream.range(0, 50).mapToObj(Integer::toString).toList());
        assertEquals(50, result.items().size());
        assertEquals(49, result.items().stream().filter(item -> item.vector() != null).count());
        assertTrue(result.items().get(21).error().contains("poison 21"));
        assertEquals(21, requests.size()); // 4 子批次 + 1 次重试 + 16 次单条隔离。
        for (int i = 0; i < 16; i++) {
            String text = Integer.toString(i);
            assertEquals(1, requests.stream().filter(request -> request.contains(text)).count());
        }
        for (int i = 0; i < 50; i++) {
            assertEquals(i, result.items().get(i).index());
            if (i != 21) assertEquals((float) i, result.items().get(i).vector().get(0));
        }
    }

    @Test void malformedItemAloneIsRecomputedAndValidNeighborsAreReused() {
        var provider = mock(EmbeddingProvider.class);
        when(provider.embed(List.of("bad", "good"))).thenReturn(java.util.Arrays.asList(null, new float[]{9, 9}));
        when(provider.embed(List.of("bad"))).thenReturn(List.of(new float[]{1, 2}));
        var result = new EmbeddingOrchestrator(provider, properties(16)).embedAll(List.of("bad", "good"));
        assertEquals(List.of(1f, 2f), result.items().get(0).vector());
        assertEquals(List.of(9f, 9f), result.items().get(1).vector());
        verify(provider).embed(List.of("bad", "good"));
        verify(provider).embed(List.of("bad"));
        verifyNoMoreInteractions(provider);
    }

    @Test void laterOutageReturnsEarlierResultsAndDoesNotTryRemainingInputs() {
        var provider = mock(EmbeddingProvider.class);
        when(provider.embed(List.of("first", "second"))).thenReturn(List.of(new float[]{1, 2}, new float[]{3, 4}));
        when(provider.embed(List.of("third", "fourth")))
                .thenThrow(new ModelUnavailable("LLM", "MODEL_UPSTREAM_UNAVAILABLE", 30, null));
        var result = new EmbeddingOrchestrator(provider, properties(2))
                .embedAll(List.of("first", "second", "third", "fourth", "fifth"));
        assertNotNull(result.unavailableReason());
        assertEquals(List.of(1f, 2f), result.items().get(0).vector());
        assertEquals(List.of(3f, 4f), result.items().get(1).vector());
        assertEquals(3, result.items().stream().filter(item -> item.error() != null).count());
        verify(provider, times(2)).embed(anyList());
    }

    @Test void isolationOutageStillRetainsValidItemsLaterInTheSameResponse() {
        var provider = mock(EmbeddingProvider.class);
        when(provider.embed(List.of("bad", "good"))).thenReturn(java.util.Arrays.asList(null, new float[]{9, 9}));
        when(provider.embed(List.of("bad"))).thenThrow(new ModelUnavailable("LLM", "MODEL_UPSTREAM_UNAVAILABLE", 30, null));
        var result = new EmbeddingOrchestrator(provider, properties(16)).embedAll(List.of("bad", "good"));
        assertNotNull(result.unavailableReason());
        assertNull(result.items().get(0).vector());
        assertEquals(List.of(9f, 9f), result.items().get(1).vector());
    }

    @Test void controllerSerializesPartialResultsWithTheirInputIndexes() throws Exception {
        var provider = mock(EmbeddingProvider.class);
        when(provider.embed(List.of("good", "bad"))).thenReturn(java.util.Arrays.asList(new float[]{1, 2}, null));
        when(provider.embed(List.of("bad"))).thenThrow(new IllegalStateException("poison"));
        var properties = properties(16);
        var controller = new InternalLlmController(properties, mock(LlmClient.class),
                new EmbeddingOrchestrator(provider, properties), new LlmRequestMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var response = controller.embeddings(new LlmPort.EmbeddingsRequest(List.of("good", "bad")));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var restored = json.readValue(json.writeValueAsBytes(response), LlmPort.EmbeddingsResponse.class);
        assertEquals(0, restored.getItems().get(0).index());
        assertEquals(List.of(1f, 2f), restored.getItems().get(0).vector());
        assertEquals(1, restored.getItems().get(1).index());
        assertTrue(restored.getItems().get(1).error().contains("poison"));
        assertNull(restored.getItems().get(1).vector());
    }

    @Test void providerKeepsValidResponseItemsWhenOneEmbeddingCannotBeParsed() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<List<String>> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/embeddings", exchange -> {
            var request = cn.diaojiahong.myyoutube.common.utils.JsonUtil.GSON.fromJson(
                    new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
            var inputs = request.getAsJsonArray("input").asList().stream().map(com.google.gson.JsonElement::getAsString).toList();
            calls.add(inputs);
            String body = inputs.size() == 2
                    ? "{\"data\":[{\"index\":1,\"embedding\":[9,9]},{\"index\":0,\"embedding\":\"invalid\"}]}"
                    : "{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var properties = properties(16);
            properties.getEmbedding().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getEmbedding().setModel("test");
            var result = new EmbeddingOrchestrator(new OpenAiCompatEmbeddingProvider(properties), properties).embedAll(List.of("bad", "good"));
            assertEquals(List.of(1f, 2f), result.items().get(0).vector());
            assertEquals(List.of(9f, 9f), result.items().get(1).vector());
            assertEquals(List.of(List.of("bad", "good"), List.of("bad")), calls);
        } finally { server.stop(0); }
    }

    private LlmProperties properties(int batchSize) {
        var properties = new LlmProperties();
        properties.getEmbedding().setDims(2);
        properties.getEmbedding().setBatchSize(batchSize);
        return properties;
    }
}

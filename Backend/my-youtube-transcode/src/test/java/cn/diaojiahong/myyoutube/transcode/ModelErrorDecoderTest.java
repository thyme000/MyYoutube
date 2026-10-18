package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.transcode.config.ModelErrorDecoder;
import feign.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelErrorDecoderTest {
    @Test void realFeignCallPreservesAdmissionCodeAndNeverRetries() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr/batch", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int invocation = calls.incrementAndGet();
            byte[] body = (invocation == 1 ? "{\"detail\":{\"code\":\"MODEL_RECOVERING\"}}" : "{\"results\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "30");
            exchange.sendResponseHeaders(invocation == 1 ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = Feign.builder().contract(new org.springframework.cloud.openfeign.support.SpringMvcContract())
                    .encoder((value, type, template) -> template.body(cn.diaojiahong.myyoutube.common.utils.JsonUtil.toJson(value)))
                    .decoder((response, type) -> cn.diaojiahong.myyoutube.common.utils.JsonUtil.GSON.fromJson(
                            response.body().asReader(StandardCharsets.UTF_8), type))
                    .retryer(Retryer.NEVER_RETRY).errorDecoder(new ModelErrorDecoder())
                    .target(cn.diaojiahong.myyoutube.transcode.api.OcrPort.class, "http://127.0.0.1:" + server.getAddress().getPort());
            var request = new cn.diaojiahong.myyoutube.transcode.api.OcrPort.Request(List.of("http://fixture/image"));
            assertEquals("MODEL_RECOVERING", assertThrows(ModelUnavailable.class, () -> client.recognize(request)).code());
            assertEquals(1, calls.get());
            assertTrue(client.recognize(request).results().isEmpty());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }
    private Exception decode(String method, int status, String body, String delay) {
        return new ModelErrorDecoder().decode(method, Response.builder().status(status).reason("fixture")
                .request(Request.create(Request.HttpMethod.POST, "http://model/ocr/batch", Map.of(), new byte[0], StandardCharsets.UTF_8))
                .headers(Map.of("Retry-After", List.of(delay))).body(body, StandardCharsets.UTF_8).build());
    }
    @Test void recognizedAdmissionCodesAreDeferredWithBoundedDelay() {
        for (String code : ModelUnavailable.ADMISSION_CODES) {
            var error = assertInstanceOf(ModelUnavailable.class, decode("OcrPort#recognize(Request)", 503,
                    "{\"detail\":{\"code\":\"" + code + "\"}}", "120"));
            assertEquals(code, error.code());
            assertEquals(60, error.retryAfterSeconds());
        }
        assertEquals(30, assertInstanceOf(ModelUnavailable.class, decode("AsrPort#transcribe(Request)", 503,
                "{\"detail\":{\"code\":\"MODEL_RECOVERING\"}}", "invalid")).retryAfterSeconds());
    }
    @Test void tagProxyPreservesLlmCircuitAndExecutionFailuresOnlyForTrustedContract() {
        var circuit=assertInstanceOf(ModelUnavailable.class,decode("ContentPipelinePort#generateTags(TagRequest)",503,
                "{\"detail\":{\"resource\":\"LLM\",\"code\":\"MODEL_CIRCUIT_OPEN\"}}","30"));
        assertTrue(circuit.terminal());
        assertEquals("LLM",circuit.resource());
        assertInstanceOf(cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure.class,
                decode("ContentPipelinePort#generateTags(TagRequest)",502,
                        "{\"detail\":{\"resource\":\"LLM\",\"code\":\"MODEL_EXECUTION_FAILED\"}}","30"));
        assertFalse(decode("ContentPipelinePort#generateTags(TagRequest)",503,
                "{\"detail\":{\"code\":\"MODEL_CIRCUIT_OPEN\"}}","30") instanceof ModelUnavailable);
    }

    @Test void ambiguousFailuresAndOtherServicesStayExecutionFailures() {
        for (String body : List.of("upstream unavailable", "{\"detail\":\"not ready\"}",
                "{\"detail\":{\"code\":\"UNKNOWN\"}}", "x".repeat(9000)))
            assertFalse(decode("OcrPort#recognize(Request)", 503, body, "30") instanceof ModelUnavailable);
        String recovering = "{\"detail\":{\"code\":\"MODEL_RECOVERING\"}}";
        assertFalse(decode("OcrPort#recognize(Request)", 502, recovering, "30") instanceof ModelUnavailable);
        assertFalse(decode("LlmPort#generate(Request)", 503, recovering, "30") instanceof ModelUnavailable);
    }
}

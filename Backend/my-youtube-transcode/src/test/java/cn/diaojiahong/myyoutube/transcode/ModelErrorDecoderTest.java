package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.feign.ModelErrorDecoder;
import cn.diaojiahong.myyoutube.common.feign.ModelErrorDecoder.Protocol;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import feign.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelErrorDecoderTest {
    final String tag = "ContentPipelinePort#generateTags(TagRequest)";
    final String ocr = "OcrPort#recognize(Request)";

    String internal(int code, String resource, String reason) {
        return "{\"code\":" + code + ",\"message\":\"模型故障\",\"data\":{\"resource\":\"" + resource
                + "\",\"reason\":\"" + reason + "\"}}";
    }
    Exception decode(Protocol protocol, String method, int status, String body, String delay) {
        return new ModelErrorDecoder(protocol).decode(method, Response.builder().status(status).reason("fixture")
                .request(Request.create(Request.HttpMethod.POST, "http://model", Map.of(), new byte[0], StandardCharsets.UTF_8))
                .headers(delay == null ? Map.of() : Map.of("retry-after", List.of(delay)))
                .body(body, StandardCharsets.UTF_8).build());
    }

    @Test void bothProtocolsKeepKnownAdmissionReasonsAndOriginalResponseCauses() {
        for (String reason : ModelUnavailable.ADMISSION_CODES) {
            for (Protocol protocol : Protocol.values()) {
                String body = protocol == Protocol.INTERNAL ? internal(50302, "LLM", reason)
                        : "{\"detail\":{\"code\":\"" + reason + "\"}}";
                var error = assertInstanceOf(ModelUnavailable.class,
                        decode(protocol, protocol == Protocol.INTERNAL ? tag : ocr, 503, body, "30"));
                assertEquals(reason, error.code());
                assertEquals(protocol == Protocol.INTERNAL ? "LLM" : "OCR", error.resource());
                assertEquals("MODEL_RECOVERY_EXHAUSTED".equals(reason), error.terminal());
                assertEquals(503, assertInstanceOf(FeignException.class, error.getCause()).status());
            }
        }
        var asr = assertInstanceOf(ModelUnavailable.class, decode(Protocol.FASTAPI, "AsrPort#transcribe(Request)",
                503, "{\"detail\":{\"code\":\"MODEL_RECOVERING\"}}", null));
        assertEquals("ASR", asr.resource());
    }

    @ParameterizedTest
    @CsvSource({"120,60", "0,5", "-3,5", "5,5", "60,60", "invalid,30", "2147483648,30"})
    void delayStaysBoundedForBothProtocols(String delay, int expected) {
        assertEquals(expected, assertInstanceOf(ModelUnavailable.class, decode(Protocol.INTERNAL, tag, 503,
                internal(50302, "LLM", "MODEL_QUEUE_FULL"), delay)).retryAfterSeconds());
        assertEquals(expected, assertInstanceOf(ModelUnavailable.class, decode(Protocol.FASTAPI, ocr, 503,
                "{\"detail\":{\"code\":\"MODEL_QUEUE_FULL\"}}", delay)).retryAfterSeconds());
    }

    @Test void tagProxyKeepsCircuitAdmissionAndExecutionSemantics() {
        for (String reason : List.of("MODEL_CIRCUIT_OPEN", "MODEL_ADMISSION_BLOCKED")) {
            var error = assertInstanceOf(ModelUnavailable.class, decode(Protocol.INTERNAL, tag, 503,
                    internal(50302, "LLM", reason), null));
            assertEquals("MODEL_CIRCUIT_OPEN".equals(reason), error.terminal());
            assertEquals(30, error.retryAfterSeconds());
        }
        var error = assertInstanceOf(ModelCallFailure.class, decode(Protocol.INTERNAL, tag, 502,
                internal(50202, "LLM", "MODEL_EXECUTION_FAILED"), null));
        assertEquals("LLM", error.resource());
        assertInstanceOf(FeignException.class, error.getCause());
    }

    @Test void protocolIsSelectedByConfigurationAndNeverGuessedFromTheBody() {
        String legacy = "{\"detail\":{\"resource\":\"LLM\",\"code\":\"MODEL_RECOVERING\"}}";
        assertInstanceOf(DependencyException.class, decode(Protocol.INTERNAL, tag, 503, legacy, "30"));
        assertInstanceOf(DependencyException.class, decode(Protocol.FASTAPI, ocr, 503,
                internal(50302, "OCR", "MODEL_RECOVERING"), "30"));
        assertInstanceOf(DependencyException.class, decode(Protocol.INTERNAL, ocr, 503,
                internal(50302, "OCR", "MODEL_RECOVERING"), "30"));
        assertInstanceOf(DependencyException.class, decode(Protocol.INTERNAL, "LlmPort#chat(ChatRequest)", 503,
                internal(50302, "LLM", "MODEL_RECOVERING"), "30"));
    }

    @Test void invalidJavaEnvelopesDoNotChangeScheduling() {
        String valid = internal(50302, "LLM", "MODEL_RECOVERING");
        for (String body : List.of("upstream unavailable", "null", "[]", "{}",
                valid.replace("50302", "50301"), valid.replace("50302", "\"50302\""),
                valid.replace("50302", "50302.1"), valid.replace("LLM", "OCR"), valid.replace("LLM", "UNKNOWN"),
                valid.replace("MODEL_RECOVERING", "UNKNOWN"), valid.replace("\"reason\"", "\"code\""),
                valid.replace("模型故障", " "), valid.replace("\"LLM\"", "3"),
                valid.replace("\"MODEL_RECOVERING\"", "{}")))
            assertInstanceOf(DependencyException.class, decode(Protocol.INTERNAL, tag, 503, body, "30"));
        assertInstanceOf(DependencyException.class, decode(Protocol.INTERNAL, tag, 502, valid, "30"));
        assertInstanceOf(DependencyException.class, decode(Protocol.INTERNAL, tag, 503,
                internal(50202, "LLM", "MODEL_EXECUTION_FAILED"), "30"));
        assertInstanceOf(DependencyException.class, decode(Protocol.INTERNAL, tag, 502,
                internal(50202, "LLM", "MODEL_RECOVERING"), "30"));
    }

    @Test void ambiguousPythonFailuresAndUntrustedMethodsStayDependencyFailures() {
        for (String body : List.of("upstream unavailable", "null", "{\"detail\":\"not ready\"}",
                "{\"detail\":{\"code\":\"UNKNOWN\"}}", "{\"detail\":{\"code\":3}}",
                "{\"detail\":{\"code\":\"MODEL_CIRCUIT_OPEN\"}}",
                "{\"detail\":{\"resource\":\"ASR\",\"code\":\"MODEL_RECOVERING\"}}"))
            assertInstanceOf(DependencyException.class, decode(Protocol.FASTAPI, ocr, 503, body, "30"));
        String valid = "{\"detail\":{\"code\":\"MODEL_RECOVERING\"}}";
        assertInstanceOf(DependencyException.class, decode(Protocol.FASTAPI, ocr, 502, valid, "30"));
        assertInstanceOf(DependencyException.class, decode(Protocol.FASTAPI, "MediaRetryPort#current()", 503, valid, "30"));
    }

    @Test void onlyBodiesWithinTheEightKiBLimitCanCarrySchedulingInformation() {
        for (Protocol protocol : Protocol.values()) {
            String body = protocol == Protocol.INTERNAL ? internal(50302, "LLM", "MODEL_RECOVERING")
                    : "{\"detail\":{\"code\":\"MODEL_RECOVERING\"}}";
            String boundary = body + " ".repeat(8192 - body.getBytes(StandardCharsets.UTF_8).length);
            assertInstanceOf(ModelUnavailable.class, decode(protocol, protocol == Protocol.INTERNAL ? tag : ocr, 503, boundary, "30"));
            assertInstanceOf(DependencyException.class, decode(protocol, protocol == Protocol.INTERNAL ? tag : ocr, 503, boundary + " ", "30"));
        }
    }

    @Test void bodyReadTimeoutKeepsItsCauseAndInterruptFlag() {
        var failure = new java.net.SocketTimeoutException("fixture read timeout");
        var stream = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw failure; }
        };
        var response = Response.builder().status(503).reason("fixture").headers(Map.of())
                .request(Request.create(Request.HttpMethod.POST, "http://model", Map.of(), new byte[0], StandardCharsets.UTF_8))
                .body(stream, null).build();
        Thread.currentThread().interrupt();
        try {
            var error = assertInstanceOf(DependencyException.class, new ModelErrorDecoder(Protocol.INTERNAL).decode(tag, response));
            assertSame(failure, error.getCause());
            assertEquals(ErrorCode.DEPENDENCY_TIMEOUT, error.getErrorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}

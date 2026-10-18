package cn.diaojiahong.myyoutube.common.observability;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PipelineLogFormatterTest {
    @Test void retainsWhitelistedContextAndRedactsMessageAndExceptionSecrets() throws Exception {
        var event = new LoggingEvent();
        event.setInstant(java.time.Instant.parse("2026-10-14T10:00:00.123456Z"));
        event.setLevel(Level.ERROR); event.setLoggerName("probe"); event.setThreadName("worker-1");
        event.setMessage("failed https://s3.example/private?signature=secret token=abc Bearer xyz");
        event.setMDCPropertyMap(Map.of("taskId", "9007199254740993", "traceId", "trace-1", "token", "secret"));
        event.setThrowableProxy(new ThrowableProxy(new IllegalArgumentException("private-transcript-token")));
        var formatter = new PipelineLogFormatter(new MockEnvironment().withProperty("spring.application.name", "transcode"));
        String result = formatter.format(event);
        var tree = new JsonMapper().readTree(result);
        assertTrue(result.endsWith("\n")); assertEquals("9007199254740993", tree.path("taskId").asText());
        assertEquals("trace-1", tree.at("/trace/id").asText());
        assertEquals("transcode", tree.at("/service/name").asText());
        assertEquals("java.lang.IllegalArgumentException", tree.at("/error/type").asText());
        assertFalse(tree.has("token")); assertFalse(result.contains("signature=secret"));
        assertFalse(result.contains("token=abc")); assertFalse(result.contains("Bearer xyz"));
        assertFalse(result.contains("private-transcript-token"));
    }
    @Test void strictRoundTripRejectsNonStringIdsAndNumericTimestamps() {
        String source = PipelineEventJson.encode(new PipelineEventTest().failed(1, true));
        assertEquals(new PipelineEventTest().failed(1, true), PipelineEventJson.decode(source));
        assertThrows(IllegalArgumentException.class, () -> PipelineEventJson.decode(source.replace("\"taskId\":\"9007199254740993\"", "\"taskId\":9007199254740993")));
        assertThrows(IllegalArgumentException.class, () -> PipelineEventJson.decode(source.replace("\"@timestamp\":\"2026-10-14T10:00:00Z\"", "\"@timestamp\":1791936000")));
    }
}

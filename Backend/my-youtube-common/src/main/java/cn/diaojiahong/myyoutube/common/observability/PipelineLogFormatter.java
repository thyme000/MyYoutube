package cn.diaojiahong.myyoutube.common.observability;

import ch.qos.logback.classic.spi.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;
import java.util.*;

public final class PipelineLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private static final JsonMapper JSON = new JsonMapper();
    private final String service;

    public PipelineLogFormatter(Environment environment) {
        service = environment.getProperty("spring.application.name", "unknown");
    }

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("@timestamp", event.getInstant().toString());
        fields.put("service", Map.of("name", service));
        fields.put("log", Map.of("level", event.getLevel().toString(), "logger", event.getLoggerName()));
        fields.put("process", Map.of("thread", Map.of("name", event.getThreadName())));
        fields.put("message", redact(event.getFormattedMessage()));
        event.getMDCPropertyMap().forEach((key, value) -> {
            if (BusinessContext.HEADERS.containsKey(key) && value != null && value.matches("[A-Za-z0-9_.:-]{1,128}")) {
                if (key.equals("traceId")) {
                    fields.put("trace", Map.of("id", value));
                } else {
                    fields.put(key, value);
                }
            }
        });

        String trace = PipelineTrace.currentId(event.getMDCPropertyMap().get("traceId"));
        if (trace != null) {
            fields.put("trace", Map.of("id", trace));
        }

        IThrowableProxy error = event.getThrowableProxy();
        if (error != null) {
            List<String> frames = Arrays.stream(error.getStackTraceElementProxyArray()).limit(40)
                    .map(frame -> frame.getStackTraceElement().toString()).toList();
            fields.put("error", Map.of("type", error.getClassName(), "stack_trace", frames));
        }
        try {
            return JSON.writeValueAsString(fields) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode log", e);
        }
    }

    static String redact(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("https?://[^\\s\"<>]+", "[redacted-url]")
                .replaceAll("(?i)\\bbearer\\s+[^\\s,;]+", "Bearer [redacted]")
                .replaceAll("(?i)\\b(token|password|secret|signature|api[_-]?key|access[_-]?key|secret[_-]?key|authorization"
                        + "|x-amz-credential|x-amz-signature)\\s*[=:]\\s*[^\\s,;]+", "$1=[redacted]");
    }
}

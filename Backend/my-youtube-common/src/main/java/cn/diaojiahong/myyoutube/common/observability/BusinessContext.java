package cn.diaojiahong.myyoutube.common.observability;

import org.slf4j.MDC;
import java.util.*;

public final class BusinessContext implements AutoCloseable {
    public static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("batchId", "X-Retry-Batch-Id"), Map.entry("pipelineId", "X-Pipeline-Id"), Map.entry("contentId", "X-Content-Id"),
            Map.entry("taskId", "X-Task-Id"), Map.entry("draftId", "X-Draft-Id"), Map.entry("audioId", "X-Audio-Id"),
            Map.entry("preparationId", "X-Preparation-Id"), Map.entry("mediaVersion", "X-Media-Version"),
            Map.entry("attempt", "X-Task-Attempt"), Map.entry("taskType", "X-Task-Type"),
            Map.entry("traceId", "X-Business-Trace-Id"));
    private final Map<String, String> previous = MDC.getCopyOfContextMap();
    private BusinessContext(Map<String, String> fields) {
        HEADERS.keySet().forEach(MDC::remove);
        fields.forEach((key, value) -> {
            if (HEADERS.containsKey(key) && value != null && value.matches("[A-Za-z0-9_.:-]{1,128}")) MDC.put(key, value);
        });
        String trace = PipelineTrace.currentId(MDC.get("traceId"));
        if (trace != null) MDC.put("traceId", trace);
    }
    public static BusinessContext open(Map<String, String> fields) { return new BusinessContext(fields); }
    @Override public void close() {
        if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
    }
}

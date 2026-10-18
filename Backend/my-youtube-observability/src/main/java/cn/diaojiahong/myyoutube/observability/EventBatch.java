package cn.diaojiahong.myyoutube.observability;

import cn.diaojiahong.myyoutube.common.observability.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;

final class EventBatch {
    static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static List<PipelineEvent> decode(byte[] body) {
        try {
            if (body == null || body.length > 4 * 1024 * 1024) throw new IllegalArgumentException();
            JsonNode root = JSON.readTree(body);
            if (!root.isObject() || root.size() != 2 || !root.path("schemaVersion").isIntegralNumber()
                    || !root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").intValue() != 1 || !root.path("events").isArray()
                    || root.path("events").isEmpty() || root.path("events").size() > 1000) throw new IllegalArgumentException();
            List<PipelineEvent> events = new ArrayList<>();
            for (JsonNode node : root.path("events")) events.add(PipelineEventJson.decode(node.toString()));
            return List.copyOf(events);
        } catch (Exception e) { throw new IngestFailure(IngestFailure.Reason.INVALID_BATCH); }
    }
    private EventBatch() {}
}

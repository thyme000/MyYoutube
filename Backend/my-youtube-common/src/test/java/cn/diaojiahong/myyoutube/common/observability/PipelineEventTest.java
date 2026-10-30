package cn.diaojiahong.myyoutube.common.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PipelineEventTest {
    PipelineEvent failed(int attempt, boolean retryable) {
        return new PipelineEvent(Instant.parse("2026-10-14T10:00:00Z"), new PipelineEvent.Service("transcode"),
                new PipelineEvent.Event("event-123", "ASR_FAILED", 5),
                new PipelineEvent.Aggregate("TASK", "9007199254740993"),
                new PipelineEvent.Trace("trace-1"), "1001", "1002", "9007199254740993", null, null, null,
                attempt, retryable, !retryable, retryable ? attempt + 1 : null,
                retryable ? Instant.parse("2026-10-14T10:00:30Z") : null,
                new PipelineEvent.State("RUNNING", retryable ? "RETRY_WAIT" : "DEAD"),
                new PipelineEvent.Failure("ASR_TIMEOUT"));
    }

    @Test
    void nestedFieldsPreserveStringIdsAndCanonicalDigest() throws Exception {
        PipelineEvent event = failed(1, true);
        String text = PipelineEventJson.encode(event);
        JsonNode tree = new ObjectMapper().readTree(text);
        assertEquals("event-123", tree.at("/event/id").asText());
        assertEquals(5, tree.at("/event/sequence").asLong());
        assertTrue(tree.get("taskId").isTextual());
        assertEquals("9007199254740993", tree.get("taskId").asText());
        assertFalse(tree.has("event.id"));
        assertFalse(tree.has("draftId"));
        JsonNode mapping = new ObjectMapper().readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../my-youtube-observability/src/main/resources/observability/pipeline-event-mappings.json")));
        assertEquals("strict", mapping.get("dynamic").asText());
        for (String key : new String[]{"pipelineId", "contentId", "taskId", "draftId", "preparationId", "mediaVersion"})
            assertEquals("keyword", mapping.at("/properties/" + key + "/type").asText());
        assertEquals("long", mapping.at("/properties/event/properties/sequence/type").asText());
        assertEquals(64, PipelineEventJson.digest(event).length());
        assertEquals(PipelineEventJson.digest(event), PipelineEventJson.digest(failed(1, true)));
        assertNotEquals(PipelineEventJson.digest(event), PipelineEventJson.digest(failed(4, false)));
    }

    @Test
    void retryExhaustionAndManualRerunKeepAttemptsExplicit() {
        assertEquals(2, failed(1, true).nextAttempt());
        assertTrue(failed(4, false).terminal());
        assertNull(failed(4, false).nextRetryAt());
        assertEquals(6, failed(5, true).nextAttempt());
        assertThrows(IllegalArgumentException.class, () -> failed(0, true));
        assertThrows(ArithmeticException.class, () -> PipelineEvent.nextSequence(Long.MAX_VALUE));
    }

    @Test
    void independentConfigurationDoesNotEnableOtherPaths() {
        PipelineObservationProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "my-youtube.observability.recording.enabled", "true",
                "my-youtube.observability.cleanup.enabled", "false")))
                .bind("my-youtube.observability", Bindable.of(PipelineObservationProperties.class)).get();
        assertTrue(props.getRecording().isEnabled());
        assertFalse(props.getDelivery().isEnabled());
        assertFalse(props.getCleanup().isEnabled());
        assertFalse(new PipelineObservationProperties().getRecording().isEnabled());
    }
}

package cn.diaojiahong.myyoutube.common.observability;

import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record PipelineEvent(@JsonProperty("@timestamp") Instant occurredAt, Service service, Event event,
                            Aggregate aggregate, Trace trace, String pipelineId, String contentId,
                            String taskId, String draftId, String preparationId, String mediaVersion,
                            Integer attempt, Boolean retryable, Boolean terminal, Integer nextAttempt,
                            Instant nextRetryAt, State state, Failure failure) {
    private static final Set<String> TYPES = Arrays.stream(MediaTaskType.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> CONTENT_CODES = Set.of("RETRY_BATCH_CREATED", "RETRY_BATCH_STARTED", "RETRY_BATCH_COMPLETED", "CONTENT_SUBMITTED", "ARTIFACTS_STORED", "PIPELINE_COMPLETED",
            "PIPELINE_WAITING_ARTIFACTS", "REVIEW_AUTO_PENDING", "REVIEW_MANUAL_PENDING", "REVIEW_APPROVED", "REVIEW_REJECTED",
            "CONTENT_BANNED", "CONTENT_AUDIO_BANNED", "DRAFT_READY", "DRAFT_FAILED", "DRAFT_SUBMITTED", "DRAFT_PROCESSING", "DRAFT_UPLOADING",
            "DRAFT_MEDIA_CHANGED", "DRAFT_EXPIRED", "PIPELINE_STARTED", "PIPELINE_RESUMED", "REVIEW_STARTED", "CONTENT_CANCELLED");

    public PipelineEvent {
        if (occurredAt == null || service == null || event == null || aggregate == null || state == null)
            throw new IllegalArgumentException("Missing event identity or state");
        if (attempt != null && attempt < 0) throw new IllegalArgumentException("Negative attempt");
        if (state.to().equals(state.from()) && !Set.of("DRAFT_MEDIA_CHANGED", "REVIEW_STARTED").contains(event.code()))
            throw new IllegalArgumentException("Unchanged state is not a transition");
        for (String id : new String[]{pipelineId, contentId, taskId, draftId, preparationId, mediaVersion}) optionalId(id);
        boolean deferred = event.code().endsWith("_DEFERRED") && TYPES.contains(event.code().replace("_DEFERRED", ""));
        if (deferred || event.code().endsWith("_FAILED") && TYPES.contains(event.code().replace("_FAILED", ""))) {
            if (attempt == null || attempt < 1 || retryable == null || terminal == null
                    || retryable.booleanValue() == terminal.booleanValue() || failure == null)
                throw new IllegalArgumentException("Attempt failure requires explicit retry semantics");
            if (retryable && (nextAttempt == null || nextAttempt != attempt + 1 || nextRetryAt == null))
                throw new IllegalArgumentException("Retryable failure requires the next execution");
            if (terminal && (nextAttempt != null || nextRetryAt != null))
                throw new IllegalArgumentException("Terminal failure cannot schedule a retry");
            if (!(retryable ? "RETRY_WAIT" : "DEAD").equals(state.to()))
                throw new IllegalArgumentException("Failure state does not match retry semantics");
            if (deferred && (!retryable || !"RUNNING".equals(state.from())))
                throw new IllegalArgumentException("Deferred execution must wait for another attempt");
        } else if (retryable != null || terminal != null || nextAttempt != null || nextRetryAt != null || failure != null) {
            throw new IllegalArgumentException("Retry metadata belongs to attempt failures");
        }
    }

    public record Service(String name) { public Service { id(name); } }
    public record Event(String id, String code, long sequence) {
        public Event {
            PipelineEvent.id(id);
            PipelineEvent.id(code);
            boolean taskCode = TYPES.stream().anyMatch(t -> Set.of(t + "_READY", t + "_STARTED", t + "_SUCCEEDED",
                    t + "_FAILED", t + "_DEFERRED", t + "_BLOCKED", t + "_RETRY_REQUESTED").contains(code));
            if (sequence < 1 || !(taskCode || CONTENT_CODES.contains(code))) throw new IllegalArgumentException("Invalid event code or sequence");
        }
    }
    public record Aggregate(String type, String id) {
        public Aggregate {
            if (!Set.of("TASK", "PIPELINE", "DRAFT", "CONTENT", "RETRY_BATCH").contains(type)) throw new IllegalArgumentException("Invalid aggregate type");
            PipelineEvent.id(id);
        }
    }
    public record Trace(String id) { public Trace { optionalId(id); } }
    public record State(String from, String to) {
        public State {
            optionalId(from); id(to);
        }
    }
    public record Failure(String code) { public Failure { id(code); } }

    public static long nextSequence(long current) {
        if (current < 0) throw new IllegalArgumentException("Negative sequence");
        return Math.incrementExact(current);
    }
    private static void optionalId(String value) { if (value != null) id(value); }
    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid identifier");
    }
}

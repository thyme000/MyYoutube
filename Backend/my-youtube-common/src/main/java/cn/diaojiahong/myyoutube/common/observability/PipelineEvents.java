package cn.diaojiahong.myyoutube.common.observability;

import cn.diaojiahong.myyoutube.common.pipeline.PipelineEventPort;
import org.slf4j.MDC;
import java.time.*;
import java.util.*;

public final class PipelineEvents implements PipelineEventPort {
    private final PipelineObservationProperties properties;
    private final PipelineEventRecorder recorder;
    private final String service;
    private final Clock clock;

    public PipelineEvents(
            PipelineObservationProperties properties,
            PipelineEventRecorder recorder,
            String service,
            Clock clock) {
        this.properties = properties;
        this.recorder = recorder;
        this.service = service;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return properties.getRecording().isEnabled();
    }

    @Override
    public long next(Long current) {
        return PipelineEvent.nextSequence(current == null ? 0 : current);
    }

    @Override
    public void record(Subject subject, long sequence, String code, String from, String to) {
        record(subject, sequence, code, from, to, null, null);
    }

    @Override
    public void record(
            Subject s,
            long sequence,
            String code,
            String from,
            String to,
            Instant retryAt,
            String failureCode) {
        if (!enabled()) {
            return;
        }

        boolean failed = failureCode != null;
        boolean retryable = failed && "RETRY_WAIT".equals(to);
        String trace = PipelineTrace.currentId(MDC.get("traceId"));
        recorder.record(new PipelineEvent(clock.instant(), new PipelineEvent.Service(service),
                new PipelineEvent.Event(UUID.randomUUID().toString(), code, sequence),
                new PipelineEvent.Aggregate(s.type(), string(s.id())),
                trace == null ? null : new PipelineEvent.Trace(trace),
                string(s.pipelineId()),
                string(s.contentId()),
                string(s.taskId()),
                string(s.draftId()),
                string(s.preparationId()),
                string(s.mediaVersion()), s.attempt(), failed ? retryable : null, failed ? !retryable : null,
                retryable ? s.attempt() + 1 : null, retryable ? retryAt : null,
                new PipelineEvent.State(from, to), failed ? new PipelineEvent.Failure(failureCode) : null));
    }

    private static String string(Long id) {
        return id == null ? null : id.toString();
    }
}

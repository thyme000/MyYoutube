package cn.diaojiahong.myyoutube.common.pipeline;

import java.time.Instant;

public interface PipelineEventPort {
    record Subject(String type, Long id, Long pipelineId, Long contentId, Long taskId,
                   Long draftId, Long preparationId, Long mediaVersion, Integer attempt) {
    }

    boolean enabled();

    long next(Long current);

    void record(Subject subject, long sequence, String code, String from, String to);

    void record(Subject subject, long sequence, String code, String from, String to,
                Instant retryAt, String failureCode);
}

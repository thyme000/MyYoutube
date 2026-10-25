package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.common.media.PipelineStatus;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import cn.diaojiahong.myyoutube.common.pipeline.PipelineEventPort;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.domain.po.Pipeline;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import cn.diaojiahong.myyoutube.transcode.mapper.PipelineMapper;
import cn.diaojiahong.myyoutube.transcode.preparation.PreparationLifecycle;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class PipelineEventJournal {
    private final MediaTaskMapper tasks;
    private final PipelineMapper pipelines;
    private final PreparationLifecycle lifecycle;
    private final PipelineEventPort events;

    public void task(MediaTask row, MediaTaskStatus from, String code, Input input, String failure) {
        if (!events.enabled()) return;
        Long draftId = null, preparationId = null, version = null;
        if (input != null && input.media().preparation() != null) {
            var prepared = input.media().preparation();
            draftId = prepared.draftId(); preparationId = prepared.preparationId(); version = prepared.mediaVersion();
        } else if ("PREPARE".equals(row.getTaskType())) {
            var preparation = lifecycle.lock(row.getOwnerId());
            draftId = preparation.getDraftId(); preparationId = preparation.getId(); version = preparation.getMediaVersion();
        } else if ("DRAFT".equals(row.getPhase()) && !MediaTaskType.fromCode(row.getTaskType()).isAudio()) draftId = row.getOwnerId();
        row.setEventSequence(events.next(row.getEventSequence()));
        // RUNNING 不会完整保存任务，此处需在同一事务中单独持久化事件序号。
        if (MediaTaskStatus.RUNNING.equals(row.getStatus())) tasks.update(null, new LambdaUpdateWrapper<MediaTask>()
                .eq(MediaTask::getId, row.getId()).set(MediaTask::getEventSequence, row.getEventSequence()));
        events.record(new PipelineEventPort.Subject("TASK", row.getId(), row.getPipelineId(),
                "DRAFT".equals(row.getPhase()) ? null : row.getOwnerId(), row.getId(), draftId, preparationId, version, row.getAttempt()),
                row.getEventSequence(), code, from == null ? null : from.getCode(), row.getStatus().getCode(),
                row.getNextRetryAt() == null ? null : row.getNextRetryAt().toInstant(ZoneOffset.UTC), failure);
    }

    public void pipeline(Pipeline pipeline, PipelineStatus from, String code, Input input) {
        if (!events.enabled()) return;
        pipeline.setEventSequence(events.next(pipeline.getEventSequence()));
        pipelines.update(null, new LambdaUpdateWrapper<Pipeline>().eq(Pipeline::getId, pipeline.getId())
                .set(Pipeline::getEventSequence, pipeline.getEventSequence()));
        var prepared = input.media().preparation();
        events.record(new PipelineEventPort.Subject("PIPELINE", pipeline.getId(), pipeline.getId(), pipeline.getContentId(), null,
                prepared == null ? null : prepared.draftId(), prepared == null ? null : prepared.preparationId(),
                prepared == null ? null : prepared.mediaVersion(), null), pipeline.getEventSequence(), code, from == null ? null : from.getCode(), pipeline.getStatus().getCode());
    }
}

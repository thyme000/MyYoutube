package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts;
import cn.diaojiahong.myyoutube.common.pipeline.PipelineEventPort;
import cn.diaojiahong.myyoutube.common.pipeline.PipelineEventCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.content.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.content.mapper.ContentUnderstandingMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContentObservation {
    private static final String SUBJECT_TYPE = "CONTENT";

    private final ContentUnderstandingMapper rows;
    private final PipelineEventPort events;

    public void record(ContentUnderstanding row, PipelineEventCode code, String from, String to) {
        if (!events.enabled() || row == null) return;
        PipelineContracts.Input input = JsonUtil.fromJson(row.getInputJson(), Input.class);
        PreparationContracts.Manifest preparation = input.media().preparation();
        row.setEventSequence(events.next(row.getEventSequence()));
        rows.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(
                ContentUnderstanding::getContentId,
                row.getContentId())
                .set(ContentUnderstanding::getEventSequence, row.getEventSequence()));
        events.record(new PipelineEventPort.Subject(SUBJECT_TYPE, row.getContentId(), row.getPipelineId(), row.getContentId(), null,
                        preparation == null ? null : preparation.draftId(),
                        preparation == null ? null : preparation.preparationId(),
                        preparation == null ? null : preparation.mediaVersion(), row.getReviewAttempt()),
                row.getEventSequence(), code.name(), from, to);
    }
}

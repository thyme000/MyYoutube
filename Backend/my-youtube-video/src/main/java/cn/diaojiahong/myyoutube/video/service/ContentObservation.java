package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import cn.diaojiahong.myyoutube.common.pipeline.PipelineEventPort;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContentObservation {
    private final ContentUnderstandingMapper rows;
    private final PipelineEventPort events;
    public void record(ContentUnderstanding row, String code, String from, String to) {
        if (!events.enabled() || row == null) return;
        var input = JsonUtil.fromJson(row.getInputJson(), Input.class);
        var preparation = input.media().preparation();
        row.setEventSequence(events.next(row.getEventSequence()));
        rows.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, row.getContentId())
                .set(ContentUnderstanding::getEventSequence, row.getEventSequence()));
        events.record(new PipelineEventPort.Subject("CONTENT", row.getContentId(), row.getPipelineId(), row.getContentId(), null,
                        preparation == null ? null : preparation.draftId(), preparation == null ? null : preparation.preparationId(),
                        preparation == null ? null : preparation.mediaVersion(), row.getReviewAttempt()),
                row.getEventSequence(), code, from, to);
    }
}

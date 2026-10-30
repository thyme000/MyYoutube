package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.pipeline.PipelineEventPort;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DraftObservation {
    private final MediaDraftMapper drafts;
    private final PipelineEventPort events;
    private final TransactionTemplate transaction;

    public int insert(MediaDraft draft) {
        if (!events.enabled()) return drafts.insert(draft);
        return Objects.requireNonNull(transaction.execute(tx -> {
            int inserted = drafts.insert(draft);
            record(null, drafts.selectById(draft.getId()));
            return inserted;
        }));
    }

    public int update(Long id, LambdaUpdateWrapper<MediaDraft> patch) {
        if (!events.enabled()) return drafts.update(null, patch);
        return Objects.requireNonNull(transaction.execute(tx -> {
            MediaDraft before = drafts.selectOne(new LambdaQueryWrapper<MediaDraft>().eq(MediaDraft::getId, id).last("FOR UPDATE"));
            int changed = drafts.update(null, patch);
            if (changed > 0) record(before, drafts.selectById(id));
            return changed;
        }));
    }

    private void record(MediaDraft before, MediaDraft after) {
        boolean mediaChanged = before != null && (!Objects.equals(before.getPreparationId(), after.getPreparationId())
                || !Objects.equals(before.getMediaVersion(), after.getMediaVersion()));
        if (before != null && before.getStatus() == after.getStatus() && !mediaChanged) return;
        long sequence = events.next(after.getEventSequence());
        drafts.update(
                null,
                new LambdaUpdateWrapper<MediaDraft>()
                        .eq(MediaDraft::getId, after.getId())
                        .set(MediaDraft::getEventSequence, sequence));
        events.record(new PipelineEventPort.Subject("DRAFT", after.getId(), null, null, null, after.getId(),
                        after.getPreparationId(), after.getMediaVersion(), after.getPreparationAttempt()), sequence,
                before != null
                        && before.getStatus() == after.getStatus() ? "DRAFT_MEDIA_CHANGED" : "DRAFT_" + after.getStatus()
                                .name(),
                before == null ? null : before.getStatus().name(), after.getStatus().name());
    }
}

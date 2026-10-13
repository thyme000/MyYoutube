package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DraftExpiryService {
    private final MediaDraftMapper drafts;
    private static final List<MediaDraftStatusEnum> EDITABLE = List.of(MediaDraftStatusEnum.UPLOADING,
            MediaDraftStatusEnum.PROCESSING, MediaDraftStatusEnum.READY, MediaDraftStatusEnum.FAILED);

    @Scheduled(fixedDelay = 1800000, initialDelay = 300000)
    public void expire() {
        var now = LocalDateTime.now();
        long cursor = 0;
        while (true) {
            var page = drafts.selectList(new LambdaQueryWrapper<MediaDraft>().gt(MediaDraft::getId, cursor)
                    .isNotNull(MediaDraft::getPreparationId).in(MediaDraft::getStatus, EDITABLE)
                    .le(MediaDraft::getExpiresAt, now).orderByAsc(MediaDraft::getId).last("LIMIT 100"));
            if (page.isEmpty()) return;
            cursor = page.get(page.size() - 1).getId();
            for (var draft : page) {
                int hit = drafts.update(null, new LambdaUpdateWrapper<MediaDraft>().eq(MediaDraft::getId, draft.getId())
                        .eq(MediaDraft::getRevision, draft.getRevision()).eq(MediaDraft::getPreparationId, draft.getPreparationId())
                        .in(MediaDraft::getStatus, EDITABLE).le(MediaDraft::getExpiresAt, now)
                        .set(MediaDraft::getStatus, MediaDraftStatusEnum.EXPIRED).setIncrBy(MediaDraft::getRevision, 1));
                if (hit > 0) log.info("Prepared draft expired draftId={}", draft.getId());
            }
        }
    }
}

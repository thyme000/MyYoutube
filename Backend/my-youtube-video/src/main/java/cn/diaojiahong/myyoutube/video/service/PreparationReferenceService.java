package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort.PreparationReference;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PreparationReferenceService {
    private final MediaDraftMapper drafts;
    private final ContentMapper contents;

    @Transactional
    public String reference(PreparationReference reference) {
        if (reference == null
                || reference.draftId() == null
                || reference.preparationId() == null
                || reference.userId() == null)
            return "UNKNOWN";
        // The draft lock also serializes submission, whose content insert is in the same transaction.
        MediaDraft draft = drafts.selectOne(new LambdaQueryWrapper<MediaDraft>().eq(MediaDraft::getId, reference.draftId()).last("FOR UPDATE"));
        if (draft == null || !Objects.equals(draft.getUserId(), reference.userId()) || draft.getMediaVersion() == null)
            return "UNKNOWN";
        if (draft.getMediaVersion() > reference.mediaVersion()) return "RELEASED";
        if (!Objects.equals(draft.getMediaVersion(), reference.mediaVersion())
                || !Objects.equals(draft.getPreparationId(), reference.preparationId())) {
            return "UNKNOWN";
        }
        if (draft.getStatus() == MediaDraftStatusEnum.EXPIRED) return "RELEASED";
        if (draft.getStatus() != MediaDraftStatusEnum.SUBMITTED) return "RETAINED";
        Content content = contents.selectById(draft.getId());
        if (content == null || !Objects.equals(content.getAuthorId(), reference.userId())) return "UNKNOWN";
        return Integer.valueOf(1).equals(content.getIsDelete()) ? "RELEASED" : "RETAINED";
    }
}

package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.video.api.MediaRetryOwnerPort;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentReviewState;
import cn.diaojiahong.myyoutube.video.config.ContentPipelineProperties;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/internal/media-retry-owner")
@RequiredArgsConstructor
public class InternalMediaRetryOwnerController implements MediaRetryOwnerPort {
    private final MediaDraftMapper drafts;
    private final ContentUnderstandingMapper understanding;
    private final IContentService contents;
    private final ContentPipelineProperties properties;

    @Override
    public Eligibility check(Owner owner) {
        if (owner == null || owner.ownerId() == null || owner.phase() == null)
            throw new IllegalArgumentException("Incomplete retry owner");
        if ("DRAFT".equals(owner.phase())) {
            MediaDraft draft = drafts.selectById(owner.ownerId());
            if (draft == null) return denied("OWNER_DELETED");
            if (draft.getExpiresAt() != null
                    && !draft.getExpiresAt().isAfter(LocalDateTime.now())) {
                return denied("OWNER_EXPIRED");
            }
            if (owner.preparationId() != null && (!Objects.equals(draft.getPreparationId(), owner.preparationId())
                    || !Objects.equals(draft.getMediaVersion(), owner.mediaVersion()))) {
                return denied("OWNER_VERSION_CHANGED");
            }
            if (draft.getStatus() == null || !Set.of("failed", "processing").contains(draft.getStatus().getCode()))
                return denied("DRAFT_STATE_CHANGED");
            return new Eligibility(true, null);
        }
        if (!Set.of("CONTENT", "REVIEW")
                .contains(owner.phase())) {
            throw new IllegalArgumentException("Unknown retry phase");
        }
        Content content = contents.getById(owner.ownerId());
        if (content == null || Integer.valueOf(1).equals(content.getIsDelete())) return denied("OWNER_DELETED");
        ContentUnderstanding row = understanding.selectById(owner.ownerId());
        if (row == null
                || !Objects.equals(row.getPipelineId(), owner.pipelineId())) {
            return denied("OWNER_VERSION_CHANGED");
        }
        if ("REVIEW".equals(owner.phase())) {
            if (!properties.isAutoReview()
                    || !Set.of(ContentReviewState.AUTO_PENDING, ContentReviewState.AUTO_RUNNING)
                    .contains(row.getState()))
                return denied("MANUAL_REVIEW_LOCKED");
        } else if (Boolean.TRUE.equals(row.getReceived()) || row.getState() != ContentReviewState.WAITING_PIPELINE)
            return denied("CONTENT_STATE_CHANGED");
        return new Eligibility(true, null);
    }

    private Eligibility denied(String reason) {
        return new Eligibility(false, reason);
    }
}

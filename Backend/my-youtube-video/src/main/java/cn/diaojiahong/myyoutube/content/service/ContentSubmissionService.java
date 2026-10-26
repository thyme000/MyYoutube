package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.content.support.ContentSubmissionRules;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.DraftSubmissionState;
import cn.diaojiahong.myyoutube.video.service.ContentUnderstandingService;
import cn.diaojiahong.myyoutube.video.service.impl.MediaDraftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentSubmissionService {
    private final MediaDraftService drafts;
    private final ContentMapper contents;
    private final ContentTypeStrategyRegistry strategies;
    private final ContentUnderstandingService understanding;
    private final TransactionTemplate transaction;
    private final ContentSubmissionRules rules;

    public record Receipt(Long contentId, Long pipelineId, String draftStatus) {}

    @ObservedBusiness(BusinessOperation.SUBMIT_CONTENT)
    public Receipt submitRequested(Long draftId, Long userId, Integer revision, LocalDateTime lease) {
        if (revision == null || lease == null) throw new BizException(ErrorCode.STATE_CONFLICT, "缺少发布版本或认领租约");
        var draft = drafts.getById(draftId);
        if (draft == null || !userId.equals(draft.getUserId())) throw new BizException(ErrorCode.NOT_FOUND, "草稿不存在");
        if (draft.getStatus() == MediaDraftStatusEnum.SUBMITTED) {
            log.debug("Content submit reused existing registration draftId={}", draftId);
            return receipt(draftId);
        }
        if (draft.getSubmissionState() != DraftSubmissionState.PENDING
                || !java.util.Objects.equals(draft.getRevision(), revision)
                || !java.util.Objects.equals(draft.getSubmissionAfter(), lease))
            throw new BizException(ErrorCode.STATE_CONFLICT, "发布请求已取消或被其他任务接管");
        if (draft.getStatus() != MediaDraftStatusEnum.READY) throw new BizException(ErrorCode.STATE_CONFLICT, "草稿尚未准备完成");
        if (!java.util.Objects.equals(draft.getSubmissionRevision(), draft.getRevision()))
            throw new BizException(ErrorCode.STATE_CONFLICT, "发布请求与草稿版本不一致");
        var content = new Content().setId(draftId).setType(draft.getType()).setAuthorId(userId)
                .setTitle(rules.title(draft.getTitle()))
                .setDescription(rules.description(draft.getDescription()))
                .setVisibility("public")
                .setAllowComment(rules.allowComment(draft.getAllowComment()))
                .setCoverObjectKey(draft.getPreparedCoverObjectKey()).setStatus(ContentStatusEnum.REVIEW).setIsDelete(0).setCreateTime(BusinessTime.now());
        var strategy = strategies.get(draft.getType());
        if (strategy == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "不支持的内容类型");
        var media = strategy.prepare(draft);
        try {
            Receipt receipt = transaction.execute(tx -> {
                strategy.validateSubmission(media, userId);
                if (!drafts.submit(draft, media.durationMs())) throw new BizException(ErrorCode.STATE_CONFLICT, "发布状态已变化，本次提交未生效");
                contents.insert(content);
                strategy.persist(draftId, media);
                return new Receipt(draftId, understanding.enroll(content, media), "submitted");
            });
            log.info("Content submit accepted draftId={} contentId={} pipelineId={} userId={} media={} durationMs={}",
                    draftId, receipt.contentId(), receipt.pipelineId(), userId, draft.getType(), media.durationMs());
            return receipt;
        } catch (BizException e) {
            var current = drafts.getById(draftId);
            if (current != null && current.getStatus() == MediaDraftStatusEnum.SUBMITTED) {
                log.debug("Content submit resolved as already submitted draftId={}", draftId);
                return receipt(draftId);
            }
            throw e;
        }
    }

    public Receipt receipt(Long id) {
        var row = understanding.get(id);
        if (row == null) throw new IllegalStateException("已提交草稿缺少加工登记");
        return new Receipt(id, row.getPipelineId(), "submitted");
    }
}

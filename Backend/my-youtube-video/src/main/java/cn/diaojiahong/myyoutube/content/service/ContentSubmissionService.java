package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.content.support.ContentSubmissionRules;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.service.ContentUnderstandingService;
import cn.diaojiahong.myyoutube.video.service.impl.MediaDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ContentSubmissionService {
    private final MediaDraftService drafts;
    private final ContentMapper contents;
    private final ContentTypeStrategyRegistry strategies;
    private final ContentUnderstandingService understanding;
    private final TransactionTemplate transaction;

    public record Receipt(Long contentId, Long pipelineId, String draftStatus) {}

    public Receipt submit(Long draftId, Long userId) {
        var draft = drafts.getById(draftId);
        if (draft == null || !userId.equals(draft.getUserId())) throw new BizException(BizException.BIZ_ERROR, "草稿不存在");
        if (draft.getStatus() == MediaDraftStatusEnum.SUBMITTED) return receipt(draftId);
        if (draft.getStatus() != MediaDraftStatusEnum.READY) throw new BizException(BizException.BIZ_ERROR, "草稿尚未准备完成");
        var content = new Content().setId(draftId).setType(draft.getType()).setAuthorId(userId)
                .setTitle(ContentSubmissionRules.title(draft.getTitle()))
                .setDescription(ContentSubmissionRules.description(draft.getDescription()))
                .setVisibility("public")
                .setAllowComment(ContentSubmissionRules.allowComment(draft.getAllowComment()))
                .setCoverObjectKey(draft.getPreparedCoverObjectKey()).setStatus("review").setIsDelete(0).setCreateTime(LocalDateTime.now());
        var strategy = strategies.get(draft.getType());
        if (strategy == null) throw new BizException(BizException.BIZ_ERROR, "不支持的内容类型");
        var media = strategy.prepare(draft);
        try {
            return transaction.execute(tx -> {
                if (!drafts.submit(draft, media.durationMs())) throw new BizException(BizException.BIZ_ERROR, "草稿已修改或已提交，请刷新");
                contents.insert(content);
                strategy.persist(draftId, media);
                return new Receipt(draftId, understanding.enroll(content, media), "submitted");
            });
        } catch (BizException e) {
            var current = drafts.getById(draftId);
            if (current != null && current.getStatus() == MediaDraftStatusEnum.SUBMITTED) return receipt(draftId);
            throw e;
        }
    }

    private Receipt receipt(Long id) {
        var row = understanding.get(id);
        if (row == null) throw new IllegalStateException("已提交草稿缺少加工登记");
        return new Receipt(id, row.getPipelineId(), "submitted");
    }
}

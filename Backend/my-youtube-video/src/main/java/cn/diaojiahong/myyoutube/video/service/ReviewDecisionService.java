package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.video.config.ContentPipelineProperties;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.media.ReviewVerdict;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import org.springframework.transaction.support.*;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentReviewState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReviewDecisionService {

    private final ContentUnderstandingMapper understanding;
    private final ContentReviewRecordMapper records;
    private final ContentPublicationEventMapper events;
    private final IContentService contents;
    private final ContentPipelineProperties properties;
    private final PublicationEffectsService effects;

    private final PromptTemplates prompts;

    @Transactional
    public boolean manual(Long id, boolean pass, String reason, Long actor) {
        if (reason == null || reason.isBlank() || reason.length() > 4000 || actor == null)
            throw new BizException(BizException.BIZ_ERROR, "必须填写审核理由");
        var row = lock(id);
        if (row == null || !Boolean.TRUE.equals(row.getReceived()) || row.getState() != ContentReviewState.MANUAL_PENDING)
            throw new BizException(BizException.BIZ_ERROR, "内容尚未转交人工审核");
        boolean changed = transition(id, pass ? ContentStatusEnum.PUBLISHED : ContentStatusEnum.BANNED, reason, false);
        if (!changed) return false;
        records.insert(new ContentReviewRecord().setContentId(id).setPipelineId(row.getPipelineId())
                .setAttempt(row.getReviewAttempt()).setSource("MANUAL").setDecision(pass ? "PASS" : "REJECT")
                .setReason(reason.trim()).setActorId(actor).setCreateTime(LocalDateTime.now()));
        understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, id)
                .set(ContentUnderstanding::getState, pass ? "APPROVED" : "REJECTED"));
        return true;
    }

    @Transactional
    public boolean machine(Long id, int attempt, ReviewVerdict verdict) {
        var row = lock(id);
        if (row == null || row.getState() != ContentReviewState.AUTO_RUNNING || row.getReviewAttempt() != attempt) return false;
        records.insert(record(row, verdict, attempt));
        boolean pass = verdict.passes(properties.getMaxRisk(), properties.getMinConfidence()) && properties.isAutoReview();
        if (pass && !transition(id, ContentStatusEnum.PUBLISHED, verdict.reason(), false)) {
            understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, id)
                    .set(ContentUnderstanding::getState, "CANCELLED"));
            return false;
        }
        understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, id)
                .set(ContentUnderstanding::getState, pass ? "APPROVED" : "MANUAL_PENDING"));
        return true;
    }

    @Transactional
    public ReviewSpec begin(Long pipelineId, int attempt) {
        var row = understanding.selectOne(new LambdaQueryWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getPipelineId, pipelineId).last("FOR UPDATE"));
        if (row == null || !Boolean.TRUE.equals(row.getReceived()) || !properties.isAutoReview()
                || !java.util.Set.of(ContentReviewState.AUTO_PENDING, ContentReviewState.AUTO_RUNNING).contains(row.getState())
                || attempt != 0 && attempt < row.getReviewAttempt()) return spec(false);
        var video = contents.getById(row.getContentId());
        if (video == null || Integer.valueOf(1).equals(video.getIsDelete())) return spec(false);
        if (attempt == 0) return spec(true);
        understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, row.getContentId())
                .set(ContentUnderstanding::getReviewAttempt, attempt).set(ContentUnderstanding::getState, "AUTO_RUNNING"));
        return spec(true);
    }

    private ReviewSpec spec(boolean enabled) {
        var templates = prompts.snapshot();
        return new ReviewSpec(enabled, properties.getReviewModel(), templates.version(), templates.render("review-system"),
                properties.getMaxRisk(), properties.getMinConfidence(), properties.getEvidenceChunkCharacters(),
                properties.isReviewVisionEnabled(), properties.getReviewFrameBatchSize());
    }

    @Transactional
    public void acceptResult(ReviewResult result) {
        var row = lock(result.contentId());
        if (row == null || !java.util.Objects.equals(row.getPipelineId(), result.pipelineId())
                || !java.util.Set.of(ContentReviewState.AUTO_PENDING, ContentReviewState.AUTO_RUNNING).contains(row.getState())
                || result.attempt() < row.getReviewAttempt()) return;
        if (!properties.isAutoReview() || result.verdictJson() != null && cn.diaojiahong.myyoutube.common.media.StructuredJson.object(result.verdictJson()).has("cancelled")) {
            understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, row.getContentId()).set(ContentUnderstanding::getState, "MANUAL_PENDING"));
            return;
        }
        if (result.error() == null) {
            if (row.getReviewAttempt() != result.attempt() || row.getState() != ContentReviewState.AUTO_RUNNING) return;
            var json = cn.diaojiahong.myyoutube.common.media.StructuredJson.object(result.verdictJson());
            machine(result.contentId(), result.attempt(), ReviewVerdict.parse(result.verdictJson(), json.get("model").getAsString(),
                    json.has("rulesVersion") ? json.get("rulesVersion").getAsString() : null));
        } else {
            boolean exists = records.selectCount(new LambdaQueryWrapper<ContentReviewRecord>().eq(ContentReviewRecord::getPipelineId, result.pipelineId())
                    .eq(ContentReviewRecord::getAttempt, result.attempt()).eq(ContentReviewRecord::getSource, "AUTO")) > 0;
            if (!exists) records.insert(new ContentReviewRecord().setContentId(row.getContentId()).setPipelineId(row.getPipelineId()).setAttempt(result.attempt())
                    .setSource("AUTO").setDecision("ERROR").setReason(result.error()).setModel(properties.getReviewModel())
                    .setCreateTime(LocalDateTime.now()));
            understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, row.getContentId())
                    .set(ContentUnderstanding::getReviewAttempt, result.attempt()).set(ContentUnderstanding::getState, result.exhausted() ? "MANUAL_PENDING" : "AUTO_PENDING"));
        }
    }

    @Transactional
    public boolean banPublished(Long id, String reason, Long actor) {
        var row = lock(id);
        if (reason == null || reason.isBlank() || actor == null) throw new BizException(BizException.BIZ_ERROR, "必须填写封禁理由");
        boolean changed = transition(id, ContentStatusEnum.BANNED, reason, true);
        if (changed) records.insert(new ContentReviewRecord().setContentId(id).setPipelineId(row == null ? null : row.getPipelineId()).setAttempt(0).setSource("MANUAL")
                .setDecision("BAN").setReason(reason).setActorId(actor).setCreateTime(LocalDateTime.now()));
        return changed;
    }

    private ContentReviewRecord record(ContentUnderstanding row, ReviewVerdict verdict, int attempt) {
        return new ContentReviewRecord().setContentId(row.getContentId()).setPipelineId(row.getPipelineId()).setAttempt(attempt)
                .setSource("AUTO").setDecision(verdict.decision()).setRiskScore(verdict.riskScore()).setConfidence(verdict.confidence())
                .setReason(verdict.reason()).setEvidenceJson(JsonUtil.toJson(verdict.evidence())).setModel(verdict.model())
                .setRulesVersion(verdict.rulesVersion()).setCreateTime(LocalDateTime.now());
    }

    private ContentUnderstanding lock(Long id) {
        return understanding.selectOne(new LambdaQueryWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, id).last("FOR UPDATE"));
    }

    private boolean transition(Long id, ContentStatusEnum target, String reason, boolean publishedBan) {
        var content = contents.getBaseMapper().selectOne(new LambdaQueryWrapper<Content>().eq(Content::getId, id).last("FOR UPDATE"));
        String expected = publishedBan ? "published" : "review";
        if (content == null || Integer.valueOf(1).equals(content.getIsDelete()) || !expected.equals(content.getStatus())) return false;
        if (!contents.lambdaUpdate().eq(Content::getId, id).eq(Content::getStatus, expected)
                .set(Content::getStatus, target.getCode()).update()) return false;
        int delta = publishedBan ? -1 : target == ContentStatusEnum.PUBLISHED ? 1 : 0;
        var event = new ContentPublicationEvent().setContentId(id).setAuthorId(content.getAuthorId()).setStatus(target.getCode())
                .setDelta(delta).setReason(reason).setUserCountDone(false).setSearchDone(false).setNoticeDone(false).setCompleted(false);
        events.insert(event);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { effects.trigger(event.getId()); }
        });
        return true;
    }
}

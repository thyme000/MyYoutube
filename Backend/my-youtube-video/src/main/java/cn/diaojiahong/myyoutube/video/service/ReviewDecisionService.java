package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
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
import cn.diaojiahong.myyoutube.content.service.AudioContentAccess;
import cn.diaojiahong.myyoutube.common.mq.message.AudioContentBanMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentReviewState;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Slf4j
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
    private final ContentObservation observation;
    private final cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings frameSettings;
    private final AudioContentAccess audioAccess;
    private final AudioMapper audios;

    @Transactional
    @ObservedBusiness(BusinessOperation.MANUAL_REVIEW)
    public boolean manual(Long id, boolean pass, String reason, Long actor) {
        if (reason == null || reason.isBlank() || reason.length() > 4000 || actor == null)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "必须填写审核理由");
        var row = lock(id);
        if (row == null || !Boolean.TRUE.equals(row.getReceived()) || row.getState() != ContentReviewState.MANUAL_PENDING)
            throw new BizException(ErrorCode.STATE_CONFLICT, "内容尚未转交人工审核");
        boolean changed = transition(id, pass ? ContentStatusEnum.PUBLISHED : ContentStatusEnum.BANNED, reason, false);
        if (!changed) return false;
        records.insert(new ContentReviewRecord().setContentId(id).setPipelineId(row.getPipelineId())
                .setAttempt(row.getReviewAttempt()).setSource("MANUAL").setDecision(pass ? "PASS" : "REJECT")
                .setReason(reason.trim()).setActorId(actor).setCreateTime(LocalDateTime.now()));
        understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, id)
                .set(ContentUnderstanding::getState, pass ? "APPROVED" : "REJECTED"));
        observation.record(row, pass ? "REVIEW_APPROVED" : "REVIEW_REJECTED", row.getState().name(), pass ? "APPROVED" : "REJECTED");
        log.info("Manual review decision contentId={} pipelineId={} attempt={} decision={} actor={} reason={}",
                id, row.getPipelineId(), row.getReviewAttempt(), pass ? "PASS" : "REJECT", actor, brief(reason));
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
            observation.record(row, "CONTENT_CANCELLED", row.getState().name(), "CANCELLED");
            log.info("Machine review cancelled contentId={} pipelineId={} attempt={} reason=CONTENT_STATE_CHANGED",
                    id, row.getPipelineId(), attempt);
            return false;
        }
        understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, id)
                .set(ContentUnderstanding::getState, pass ? "APPROVED" : "MANUAL_PENDING"));
        observation.record(row, pass ? "REVIEW_APPROVED" : "REVIEW_MANUAL_PENDING", row.getState().name(), pass ? "APPROVED" : "MANUAL_PENDING");
        log.info("Machine review decision contentId={} pipelineId={} attempt={} decision={} risk={} confidence={} maxRisk={} minConfidence={} model={} reason={}",
                id, row.getPipelineId(), attempt, pass ? "PASS" : "MANUAL", verdict.riskScore(), verdict.confidence(),
                properties.getMaxRisk(), properties.getMinConfidence(), verdict.model(), brief(verdict.reason()));
        return true;
    }

    @Transactional
    public ReviewSpec begin(Long pipelineId, int attempt) {
        var row = understanding.selectOne(new LambdaQueryWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getPipelineId, pipelineId).last("FOR UPDATE"));
        if (row == null) {
            log.debug("Review start rejected pipelineId={} reason=NOT_REGISTERED", pipelineId);
            return spec(false);
        }
        if (!Boolean.TRUE.equals(row.getReceived())) {
            log.debug("Review start rejected pipelineId={} contentId={} reason=ARTIFACTS_NOT_RECEIVED", pipelineId, row.getContentId());
            return spec(false);
        }
        if (!properties.isAutoReview()) {
            log.debug("Review start rejected pipelineId={} contentId={} reason=AUTO_REVIEW_DISABLED", pipelineId, row.getContentId());
            return spec(false);
        }
        if (!java.util.Set.of(ContentReviewState.AUTO_PENDING, ContentReviewState.AUTO_RUNNING).contains(row.getState())) {
            log.debug("Review start rejected pipelineId={} contentId={} reason=STATE_{}", pipelineId, row.getContentId(), row.getState());
            return spec(false);
        }
        if (attempt != 0 && attempt < row.getReviewAttempt()) {
            log.debug("Review start rejected pipelineId={} contentId={} attempt={} currentAttempt={} reason=STALE_ATTEMPT",
                    pipelineId, row.getContentId(), attempt, row.getReviewAttempt());
            return spec(false);
        }
        var video = contents.getById(row.getContentId());
        if (video == null || Integer.valueOf(1).equals(video.getIsDelete())) {
            log.debug("Review start rejected pipelineId={} contentId={} reason=CONTENT_DELETED", pipelineId, row.getContentId());
            return spec(false);
        }
        if (attempt == 0) return spec(true);
        understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, row.getContentId())
                .set(ContentUnderstanding::getReviewAttempt, attempt).set(ContentUnderstanding::getState, "AUTO_RUNNING"));
        if (row.getState() != ContentReviewState.AUTO_RUNNING || row.getReviewAttempt() != attempt) {
            row.setReviewAttempt(attempt);
            observation.record(row, "REVIEW_STARTED", row.getState().name(), "AUTO_RUNNING");
        }
        return spec(true);
    }

    private ReviewSpec spec(boolean enabled) {
        var templates = prompts.snapshot();
        return new ReviewSpec(enabled, properties.getReviewModel(), templates.version(), templates.render("review-system"),
                properties.getMaxRisk(), properties.getMinConfidence(), properties.getEvidenceChunkCharacters(),
                properties.isReviewVisionEnabled(), frameSettings.snapshot().reviewBatchSize());
    }

    @Transactional
    public void acceptResult(ReviewResult result) {
        var row = lock(result.contentId());
        if (row == null || !java.util.Objects.equals(row.getPipelineId(), result.pipelineId())
                || !java.util.Set.of(ContentReviewState.AUTO_PENDING, ContentReviewState.AUTO_RUNNING).contains(row.getState())
                || result.attempt() < row.getReviewAttempt()) {
            log.debug("Review result ignored contentId={} pipelineId={} attempt={} reason=STALE_OR_STATE_MISMATCH",
                    result.contentId(), result.pipelineId(), result.attempt());
            return;
        }
        if (!properties.isAutoReview() || result.verdictJson() != null && cn.diaojiahong.myyoutube.common.media.StructuredJson.object(result.verdictJson()).has("cancelled")) {
            understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, row.getContentId()).set(ContentUnderstanding::getState, "MANUAL_PENDING"));
            observation.record(row, "REVIEW_MANUAL_PENDING", row.getState().name(), "MANUAL_PENDING");
            log.info("Review handed to manual contentId={} pipelineId={} attempt={} reason={}",
                    row.getContentId(), row.getPipelineId(), result.attempt(),
                    properties.isAutoReview() ? "TASK_CANCELLED" : "AUTO_REVIEW_DISABLED");
            return;
        }
        if (result.error() == null) {
            if (row.getReviewAttempt() != result.attempt() || row.getState() != ContentReviewState.AUTO_RUNNING) {
                log.debug("Review result ignored contentId={} pipelineId={} attempt={} currentAttempt={} reason=ATTEMPT_NOT_STARTED",
                        row.getContentId(), row.getPipelineId(), result.attempt(), row.getReviewAttempt());
                return;
            }
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
            String target = result.exhausted() ? "MANUAL_PENDING" : "AUTO_PENDING";
            if (!row.getState().name().equals(target)) {
                row.setReviewAttempt(result.attempt());
                observation.record(row, result.exhausted() ? "REVIEW_MANUAL_PENDING" : "REVIEW_AUTO_PENDING", row.getState().name(), target);
            }
            log.warn("Review attempt failed contentId={} pipelineId={} attempt={} exhausted={} next={} error={}",
                    row.getContentId(), row.getPipelineId(), result.attempt(), result.exhausted(), target,
                    result.error() == null ? null : brief(result.error()));
        }
    }

    @Transactional
    @ObservedBusiness(BusinessOperation.BAN_CONTENT)
    public boolean banPublished(Long id, String reason, Long actor) {
        var row = lock(id);
        if (reason == null || reason.isBlank() || actor == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "必须填写封禁理由");
        boolean changed = transition(id, ContentStatusEnum.BANNED, reason, true);
        if (changed) {
            records.insert(new ContentReviewRecord().setContentId(id).setPipelineId(row == null ? null : row.getPipelineId()).setAttempt(0).setSource("MANUAL")
                    .setDecision("BAN").setReason(reason).setActorId(actor).setCreateTime(LocalDateTime.now()));
            observation.record(row, "CONTENT_BANNED", "published", "banned");
            log.info("Published content banned contentId={} actor={} reason={}", id, actor, brief(reason));
        }
        return changed;
    }

    @Transactional
    public boolean banByAudio(AudioContentBanMessage message) {
        ContentUnderstanding row = lock(message.contentId());
        Content content = contents.getBaseMapper().selectOne(new LambdaQueryWrapper<Content>().eq(Content::getId, message.contentId()).last("FOR UPDATE"));
        if (content == null || !Integer.valueOf(0).equals(content.getIsDelete())
                || (content.getStatus() != ContentStatusEnum.PUBLISHED && content.getStatus() != ContentStatusEnum.REVIEW)) return false;
        if (!message.audioId().equals(content.getAudioId())) return false;
        Audio audio = audios.selectOne(new LambdaQueryWrapper<Audio>().eq(Audio::getId, message.audioId()).last("FOR UPDATE"));
        if (audio == null || audio.getReviewStatus() != AudioReviewStatus.BANNED
                || audio.getIndexVersion() < message.audioVersion()) return false;
        String reason = "关联音频已被封禁，audioId=" + audio.getId();
        if (!transition(content.getId(), ContentStatusEnum.BANNED, reason, content.getStatus() == ContentStatusEnum.PUBLISHED)) return false;
        records.insert(new ContentReviewRecord().setContentId(content.getId()).setPipelineId(row == null ? null : row.getPipelineId())
                .setAttempt(0).setSource("AUDIO").setDecision("BAN").setReason(reason)
                .setEvidenceJson(JsonUtil.toJson(message)).setCreateTime(LocalDateTime.now()));
        if (row != null) understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, content.getId())
                .set(ContentUnderstanding::getState, ContentReviewState.CANCELLED));
        observation.record(row, "CONTENT_AUDIO_BANNED", content.getStatus().getCode(), "banned");
        return true;
    }

    /** 审核理由由模型或人工生成, 可能很长: 日志只留开头, 全文在审核记录表里 */
    private static String brief(String text) {
        if (text == null) return null;
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
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
        ContentStatusEnum expected = publishedBan ? ContentStatusEnum.PUBLISHED : ContentStatusEnum.REVIEW;
        if (content == null || Integer.valueOf(1).equals(content.getIsDelete()) || content.getStatus() != expected) return false;
        if (target == ContentStatusEnum.PUBLISHED && audioAccess.bannedForPublication(content)) return false;
        if (!contents.lambdaUpdate().eq(Content::getId, id).eq(Content::getStatus, expected)
                .setSql("index_version = index_version + 1")
                .set(Content::getStatus, target).update()) return false;
        int delta = publishedBan ? -1 : target == ContentStatusEnum.PUBLISHED ? 1 : 0;
        var event = new ContentPublicationEvent().setContentId(id).setAuthorId(content.getAuthorId()).setStatus(target)
                .setDelta(delta).setReason(reason).setUserCountDone(false).setSearchDone(false).setNoticeDone(false).setCompleted(false);
        events.insert(event);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { effects.trigger(event.getId()); }
        });
        return true;
    }
}

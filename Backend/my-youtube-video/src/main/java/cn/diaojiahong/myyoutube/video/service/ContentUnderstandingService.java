package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.*;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.recommend.auto.TagResultMapper;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import cn.diaojiahong.myyoutube.video.config.ContentPipelineProperties;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentReviewState;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentUnderstandingService {
    private final ContentUnderstandingMapper understanding;
    private final cn.diaojiahong.myyoutube.content.mapper.ContentMapper contents;
    private final ContentReviewRecordMapper records;
    private final ContentPipelineProperties properties;
    private final MediaTaskPort tasks;
    private final ContentArtifactStore artifacts;
    private final TagResultMapper tagMapper;
    private final IContentTagService contentTags;
    private final RocketMQTemplate mq;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    private final ContentObservation observation;

    public Long enroll(cn.diaojiahong.myyoutube.content.domain.po.Content content, ContentMedia media) {
        long id = IdWorker.getId(), submitted = System.currentTimeMillis();
        var input = new Input(id, content.getId(), content.getAuthorId(), content.getTitle(),
                content.getDescription(), content.getTags(), content.getCoverObjectKey(), media, submitted);
        MediaInputs.validate(media, content.getCoverObjectKey());
        var row = new ContentUnderstanding().setContentId(content.getId()).setPipelineId(id)
                .setState(ContentReviewState.WAITING_PIPELINE).setInputJson(JsonUtil.toJson(input))
                .setSubmissionAck(false).setReceived(false).setRetryRequested(false).setReviewAttempt(0)
                .setSubmittedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(submitted), ZoneOffset.UTC));
        understanding.insert(row);
        observation.record(row, "CONTENT_SUBMITTED", null, "WAITING_PIPELINE");
        afterCommit(() -> log.info("Content submitted for understanding contentId={} pipelineId={} authorId={} media={}",
                content.getId(), id, content.getAuthorId(), media.type()));
        afterCommit(() -> submit(input));
        return id;
    }
    private void submit(Input input) {
        mq.syncSend(MqConstants.TOPIC_CONTENT_SUBMITTED, new ContentSubmittedMessage(input));
        var ack = new TransactionTemplate(transaction.getTransactionManager());
        ack.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ack.executeWithoutResult(tx -> understanding.update(null, new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getPipelineId, input.pipelineId())
                .set(ContentUnderstanding::getSubmissionAck, true)));
    }
    public void accept(ContentUnderstoodMessage event) {
        var manifest = Objects.requireNonNull(event.manifest());
        var current = understanding.selectById(manifest.contentId());
        if (current == null || !Objects.equals(current.getPipelineId(), manifest.pipelineId())) throw new IllegalArgumentException("Unknown Pipeline");
        if (Boolean.TRUE.equals(current.getReceived()) || current.getState() == ContentReviewState.CANCELLED) {
            log.debug("Pipeline manifest ignored contentId={} pipelineId={} reason={}", manifest.contentId(), manifest.pipelineId(),
                    Boolean.TRUE.equals(current.getReceived()) ? "ALREADY_RECEIVED" : "CANCELLED");
            tasks.received(manifest.pipelineId()); return;
        }
        if (!"COMPLETED".equals(manifest.status()) || !manifest.results().keySet().equals(Set.of("COVER", "ASR", "OCR", "SUMMARY", "TAG")))
            throw new IllegalArgumentException("Incomplete Pipeline manifest");
        if (!manifest.input().equals(JsonUtil.fromJson(current.getInputJson(), Input.class))) throw new IllegalArgumentException("Pipeline input mismatch");
        var asr = StructuredJson.object(manifest.results().get("ASR"));
        var ocr = StructuredJson.object(manifest.results().get("OCR"));
        var cover = StructuredJson.object(manifest.results().get("COVER"));
        var summaryPointer = StructuredJson.object(manifest.results().get("SUMMARY"));
        var tagPointer = StructuredJson.object(manifest.results().get("TAG"));
        var summary = artifacts.read(summaryPointer.get("artifactKey").getAsString(), ContentArtifacts.Summary.class);
        var rawTags = artifacts.read(tagPointer.get("artifactKey").getAsString(), com.google.gson.JsonObject.class).toString();
        TagResultMapper.validate(rawTags);
        if (summary == null || summary.summary() == null || summary.chapters() == null || cover.get("coverObjectKey").getAsString().isBlank())
            throw new IllegalArgumentException("Malformed Pipeline artifacts");
        String coverKey = cover.get("coverObjectKey").getAsString();
        MediaInputs.objectKey(coverKey);
        if (manifest.input().media().dynamic() != null && !coverKey.equals(manifest.input().coverObjectKey()))
            throw new IllegalArgumentException("图集封面与提交快照不一致");
        if (manifest.input().media().video() != null && manifest.input().media().preparation() != null
                && manifest.input().media().preparation().items().stream().noneMatch(i -> "FRAME".equals(i.role()) && coverKey.equals(i.objectKey())))
            throw new IllegalArgumentException("视频封面不属于公共准备帧");
        Long receivedAt = transaction.execute(tx -> {
            var row = locked(manifest.contentId());
            if (!Objects.equals(row.getPipelineId(), manifest.pipelineId()) || Boolean.TRUE.equals(row.getReceived())
                    || row.getState() == ContentReviewState.CANCELLED) return null;
            var video = contents.selectById(manifest.contentId());
            if (video == null || Integer.valueOf(1).equals(video.getIsDelete())) {
                understanding.update(null, patch(row).set(ContentUnderstanding::getState, "CANCELLED"));
                observation.record(row, "CONTENT_CANCELLED", row.getState().name(), "CANCELLED");
                log.info("Pipeline artifacts discarded contentId={} pipelineId={} reason=CONTENT_DELETED",
                        manifest.contentId(), manifest.pipelineId());
                return null;
            }
            var mapped = tagMapper.map(rawTags);
            contentTags.saveAutoTags(video.getId(), mapped.weights());
            contents.update(null, new LambdaUpdateWrapper<cn.diaojiahong.myyoutube.content.domain.po.Content>().eq(cn.diaojiahong.myyoutube.content.domain.po.Content::getId, video.getId())
                    .set(cn.diaojiahong.myyoutube.content.domain.po.Content::getCoverObjectKey, cover.get("coverObjectKey").getAsString())
                    .set(cn.diaojiahong.myyoutube.content.domain.po.Content::getTags, String.join(",", mapped.names())).set(cn.diaojiahong.myyoutube.content.domain.po.Content::getCategoryCode, mapped.categoryCode()));
            long readyAt = System.currentTimeMillis();
            understanding.update(null, patch(row).set(ContentUnderstanding::getSubtitleKey, asr.get("vttKey").getAsString())
                    .set(ContentUnderstanding::getSegmentsKey, asr.get("segmentsKey").getAsString()).set(ContentUnderstanding::getOcrKey, ocr.get("artifactKey").getAsString())
                    .set(ContentUnderstanding::getSummaryKey, summaryPointer.get("artifactKey").getAsString()).set(ContentUnderstanding::getTagKey, tagPointer.get("artifactKey").getAsString())
                    .set(ContentUnderstanding::getSummary, summary.summary()).set(ContentUnderstanding::getChaptersJson, JsonUtil.toJson(summary.chapters()))
                    .set(ContentUnderstanding::getReceived, true).set(ContentUnderstanding::getRetryRequested, false)
                    .set(ContentUnderstanding::getReceivedAt, LocalDateTime.ofInstant(Instant.ofEpochMilli(readyAt), ZoneOffset.UTC))
                    .set(ContentUnderstanding::getState, properties.isAutoReview() ? "AUTO_PENDING" : "MANUAL_PENDING"));
            observation.record(row, "ARTIFACTS_STORED", row.getState().name(), properties.isAutoReview() ? "AUTO_PENDING" : "MANUAL_PENDING");
            if (properties.isAutoReview()) afterCommit(() -> requestReview(manifest.pipelineId()));
            return readyAt;
        });
        if (receivedAt != null) {
            long readyElapsedMs = Math.max(0, receivedAt - manifest.input().submittedAt());
            metrics.timer("media.pipeline.ready.duration").record(readyElapsedMs, TimeUnit.MILLISECONDS);
            log.info("Pipeline artifacts stored contentId={} pipelineId={} review={} readyElapsedMs={}",
                    manifest.contentId(), manifest.pipelineId(), properties.isAutoReview() ? "AUTO" : "MANUAL", readyElapsedMs);
        } else {
            log.debug("Pipeline artifacts not stored contentId={} pipelineId={} reason=STALE_OR_CANCELLED",
                    manifest.contentId(), manifest.pipelineId());
        }
        tasks.received(manifest.pipelineId());
    }
    private void requestReview(Long pipelineId) { mq.syncSend(MqConstants.TOPIC_REVIEW_REQUESTED, new ReviewRequestedMessage(pipelineId)); }
    public boolean retry(Long videoId) {
        var row = understanding.selectById(videoId);
        if (row == null || Boolean.TRUE.equals(row.getReceived()) || row.getState() != ContentReviewState.WAITING_PIPELINE) {
            log.debug("Pipeline retry rejected contentId={} reason={}", videoId, row == null ? "NOT_REGISTERED"
                    : Boolean.TRUE.equals(row.getReceived()) ? "ARTIFACTS_ALREADY_RECEIVED" : "STATE_" + row.getState());
            return false;
        }
        var pipeline = tasks.pipeline(row.getPipelineId());
        if (pipeline == null || !"WAITING_ARTIFACTS".equals(pipeline.status())) {
            log.debug("Pipeline retry rejected contentId={} pipelineId={} reason=PIPELINE_{}", videoId, row.getPipelineId(),
                    pipeline == null ? "MISSING" : pipeline.status());
            return false;
        }
        transaction.executeWithoutResult(tx -> understanding.update(null, patch(row).eq(ContentUnderstanding::getReceived, false)
                .set(ContentUnderstanding::getRetryRequested, true)));
        return retryRequested(row);
    }
    private boolean retryRequested(ContentUnderstanding row) {
        boolean applied = tasks.retry(row.getPipelineId());
        if (applied) understanding.update(null, patch(row).set(ContentUnderstanding::getRetryRequested, false));
        return applied;
    }
    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void reconcile() {
        if (!properties.isAutoReview()) handoffReviews();
        long cursor = 0;
        while (true) {
            var rows = understanding.selectList(new LambdaQueryWrapper<ContentUnderstanding>().gt(ContentUnderstanding::getContentId, cursor)
                    .and(q -> q.eq(ContentUnderstanding::getSubmissionAck, false).or().eq(ContentUnderstanding::getRetryRequested, true)
                            .or().eq(ContentUnderstanding::getState, "AUTO_PENDING"))
                    .orderByAsc(ContentUnderstanding::getContentId).last("LIMIT 100"));
            if (rows.isEmpty()) break;
            cursor = rows.get(rows.size()-1).getContentId();
            for (var row : rows) try {
                if (!Boolean.TRUE.equals(row.getSubmissionAck())) submit(JsonUtil.fromJson(row.getInputJson(), Input.class));
                if (Boolean.TRUE.equals(row.getRetryRequested())) retryRequested(row);
                if (properties.isAutoReview() && row.getState() == ContentReviewState.AUTO_PENDING) requestReview(row.getPipelineId());
            } catch (Exception e) { log.error("Pipeline registration recovery failed pipelineId={}", row.getPipelineId(), e); }
        }
    }
    public Map<String, Object> status(Long videoId) {
        var row = understanding.selectById(videoId);
        if (row == null) throw new BizException(BizException.BIZ_ERROR, "内容尚未提交加工");
        var result = new LinkedHashMap<String, Object>();
        result.put("understanding", row); result.put("pipeline", tasks.pipeline(row.getPipelineId()));
        result.put("reviews", records.selectList(new LambdaQueryWrapper<ContentReviewRecord>().eq(ContentReviewRecord::getContentId, videoId).orderByAsc(ContentReviewRecord::getCreateTime)));
        return result;
    }
    public List<MediaTaskPort.PipelineView> waiting(int page, int size) { return tasks.waiting(page, size); }
    private void handoffReviews() {
        long cursor = 0;
        while (true) {
            var page = understanding.selectList(new LambdaQueryWrapper<ContentUnderstanding>().gt(ContentUnderstanding::getContentId, cursor)
                    .in(ContentUnderstanding::getState, "AUTO_PENDING", "AUTO_RUNNING").orderByAsc(ContentUnderstanding::getContentId).last("LIMIT 100"));
            if (page.isEmpty()) return;
            cursor = page.get(page.size() - 1).getContentId();
            for (var candidate : page) transaction.executeWithoutResult(tx -> {
                var row = locked(candidate.getContentId());
                if (row == null || !Set.of(ContentReviewState.AUTO_PENDING, ContentReviewState.AUTO_RUNNING).contains(row.getState())) return;
                understanding.update(null, patch(row).set(ContentUnderstanding::getState, "MANUAL_PENDING"));
                observation.record(row, "REVIEW_MANUAL_PENDING", row.getState().name(), "MANUAL_PENDING");
            });
        }
    }
    public ContentUnderstanding locked(Long id) { return understanding.selectOne(new LambdaQueryWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, id).last("FOR UPDATE")); }
    public ContentUnderstanding get(Long id) { return understanding.selectById(id); }
    private LambdaUpdateWrapper<ContentUnderstanding> patch(ContentUnderstanding row) { return new LambdaUpdateWrapper<ContentUnderstanding>().eq(ContentUnderstanding::getContentId, row.getContentId()).eq(ContentUnderstanding::getPipelineId, row.getPipelineId()); }
    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { action.run(); } catch (Exception e) { log.error("Pipeline notification deferred to recovery", e); }
            }
        });
    }
}

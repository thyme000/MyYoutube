package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryBatchStatus;
import cn.diaojiahong.myyoutube.common.media.PipelineStatus;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.domain.po.*;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaTaskService {
    private final MediaTaskMapper tasks;
    private final cn.diaojiahong.myyoutube.transcode.preparation.PreparationLifecycle lifecycle;
    private final PipelineMapper pipelines;
    private final TransactionTemplate transaction;
    private final MediaTaskDispatcher dispatcher;
    private final PipelineProperties properties;
    private final MeterRegistry metrics;
    private final cn.diaojiahong.myyoutube.video.api.ContentPipelinePort video;
    private final PipelineEventJournal eventJournal;

    @Autowired(required = false)
    private RetryBatchStore retryBatches;
    @Autowired(required = false)
    private RetryOwnerChecks retryOwners;

    public void startAudioPreparation(AudioPreparationContracts.Request request) {
        request.validate();
        try { transaction.executeWithoutResult(tx -> {
            var row = audioPreparation(request.audioId());
            if (row != null) {
                if (!request.equals(JsonUtil.fromJson(row.getDraftInputJson(), AudioPreparationContracts.Request.class)))
                    throw new IllegalArgumentException("audioId input mismatch");
                return;
            }
            row = new MediaTask().setOwnerId(request.audioId()).setPhase("DRAFT").setTaskType(MediaTaskType.AUDIO_PREPARE.name())
                    .setStatus(MediaTaskStatus.PENDING).setAttempt(0).setRetryCount(0).setCompletionAck(false)
                    .setDraftInputJson(JsonUtil.toJson(request)).setDependenciesJson("{}");
            tasks.insert(row);
            ready(row, Map.of(), null);
        }); } catch (DuplicateKeyException duplicate) {
            var existing = audioPreparation(request.audioId());
            if (existing == null) throw duplicate;
            if (!request.equals(JsonUtil.fromJson(existing.getDraftInputJson(), AudioPreparationContracts.Request.class)))
                throw new IllegalArgumentException("audioId input mismatch");
        }
    }

    public AudioPreparationContracts.View audioPreparationView(Long id) {
        var row = audioPreparation(id);
        return row == null ? null : new AudioPreparationContracts.View(id, row.getStatus(), row.getAttempt(), row.getRetryCount(),
                row.getResultJson() == null ? null : JsonUtil.fromJson(row.getResultJson(), AudioPreparationContracts.Result.class),
                row.getErrorMessage(), row.getFailureCode());
    }

    public boolean retryAudioPreparation(Long id) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var row = tasks.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getOwnerId, id)
                    .eq(MediaTask::getPhase, "DRAFT").eq(MediaTask::getTaskType, MediaTaskType.AUDIO_PREPARE.name()).last("FOR UPDATE"));
            if (row == null) return false;
            if (Set.of(MediaTaskStatus.READY, MediaTaskStatus.RUNNING, MediaTaskStatus.RETRY_WAIT, MediaTaskStatus.SUCCESS).contains(row.getStatus())) return true;
            if (row.getStatus() != MediaTaskStatus.DEAD) return false;
            row.setRetryCount(0).setErrorMessage(null).setFailureCode(null).setCompletionAck(false).setUnavailableDeadline(null);
            ready(row, Map.of(), null);
            return true;
        }));
    }

    private MediaTask audioPreparation(Long id) {
        return tasks.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getOwnerId, id)
                .eq(MediaTask::getPhase, "DRAFT").eq(MediaTask::getTaskType, MediaTaskType.AUDIO_PREPARE.name()));
    }

    public void rejectAudioPreparation(MediaTaskMessage message, InvalidMediaInputException.Reason reason) {
        Objects.requireNonNull(reason);
        transaction.executeWithoutResult(tx -> {
            var row = tasks.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getId, message.getTaskId()).last("FOR UPDATE"));
            if (row == null || !MediaTaskType.AUDIO_PREPARE.matches(row.getTaskType()) || row.getStatus() != MediaTaskStatus.RUNNING
                    || !Objects.equals(row.getAttempt(), message.getAttempt())) return;
            row.setStatus(MediaTaskStatus.DEAD).setFailureCode(reason.code())
                    .setErrorMessage(reason.code()).setNextRetryAt(null)
                    .setCompletionAck(true).setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
            eventJournal.task(row, MediaTaskStatus.RUNNING, MediaTaskType.AUDIO_PREPARE.name() + "_FAILED", null, reason.code());
            save(row);
        });
    }

    public void start(Input input) {
        if (input == null || input.pipelineId() == null || input.contentId() == null || input.userId() == null
                || input.media() == null || input.submittedAt() <= 0)
            throw new IllegalArgumentException("Incomplete immutable Pipeline input");
        MediaInputs.validate(input.media(), input.coverObjectKey());
        if (input.media().preparation() != null) {
            PreparedMedia.validate(input.media(), input.coverObjectKey());
            if (!Objects.equals(input.media().preparation().draftId(), input.contentId())
                    || !Objects.equals(input.media().preparation().userId(), input.userId()))
                throw new IllegalArgumentException("Prepared Pipeline owner mismatch");
        }
        transaction.executeWithoutResult(tx -> {
            lock(input.pipelineId());
            lifecycle.requireActive(input.media());
            var pipeline = new Pipeline().setId(input.pipelineId()).setContentId(input.contentId())
                    .setInputJson(JsonUtil.toJson(input)).setStatus(PipelineStatus.PROCESSING).setReceived(false)
                    .setSubmittedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(input.submittedAt()), ZoneOffset.UTC));
            try {
                pipelines.insert(pipeline);
            } catch (DuplicateKeyException e) {
                var current = pipelines.selectById(input.pipelineId());
                if (!input.equals(JsonUtil.fromJson(current.getInputJson(), Input.class))) throw new IllegalStateException("pipelineId input mismatch");
                log.debug("Pipeline already registered, submit replayed pipelineId={}", input.pipelineId());
                return;
            }
            eventJournal.pipeline(pipeline, null, "PIPELINE_STARTED", input);
            var nodes = new ArrayList<MediaTask>();
            for (var type : MediaTaskType.contentTypes()) nodes.add(insert(input.contentId(), input.pipelineId(), "CONTENT", type, input));
            afterCommit(() -> log.info("Pipeline accepted pipelineId={} contentId={} userId={} media={} nodes={}",
                    input.pipelineId(), input.contentId(), input.userId(), input.media().type(), nodes.size()));
            advance(pipeline, nodes);
        });
    }
    public void startPreparation(Long preparationId, Long userId, ContentMedia media, String cover) {
        transaction.executeWithoutResult(tx -> {
            lifecycle.requireActive(preparationId);
            var input = new Input(null, preparationId, userId, "", "", "", cover, media, System.currentTimeMillis());
            var row = insert(preparationId, null, "DRAFT", MediaTaskType.PREPARE, input);
            afterCommit(() -> log.info("Preparation task accepted preparationId={} userId={} media={}", preparationId, userId, media.type()));
            ready(row, Map.of(), input);
        });
    }
    public boolean retryPreparation(Long preparationId) {
        if (retryBatches != null) for (var row : listByDraft(preparationId))
            if (row.getRetryBatchId() != null) retryBatches.batch(row.getRetryBatchId());
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            lifecycle.requireActive(preparationId);
            var row = tasks.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getOwnerId, preparationId)
                    .eq(MediaTask::getPhase, "DRAFT").eq(MediaTask::getTaskType, "PREPARE").last("FOR UPDATE"));
            if (row == null) { log.debug("Preparation retry ignored: no task preparationId={}", preparationId); return false; }
            if (Set.of(MediaTaskStatus.READY, MediaTaskStatus.RUNNING, MediaTaskStatus.RETRY_WAIT).contains(row.getStatus())) {
                log.debug("Preparation retry ignored: task still active preparationId={} status={}", preparationId, row.getStatus());
                return true;
            }
            if (!MediaTaskStatus.DEAD.equals(row.getStatus())) {
                log.debug("Preparation retry rejected: task not exhausted preparationId={} status={}", preparationId, row.getStatus());
                return false;
            }
            if (retryBatches != null && row.getRetryBatchId() != null && Set.of(MediaRetryBatchStatus.QUEUED,MediaRetryBatchStatus.RUNNING).contains(retryBatches.status(row.getRetryBatchId()))) return false;
            row.setRetryBatchId(null).setFailureCode(null).setDependencyService(null);
            row.setRetryCount(0).setErrorMessage(null).setCompletionAck(false).setUnavailableDeadline(null);
            afterCommit(() -> log.info("Preparation retry accepted preparationId={} attempt={}", preparationId, row.getAttempt()));
            ready(row, Map.of(), input(row, null));
            return true;
        }));
    }
    public void startReview(Long pipelineId) {
        if (!video.reviewSpec(pipelineId, 0).enabled()) {
            log.debug("Review skipped: policy disabled pipelineId={}", pipelineId);
            return;
        }
        transaction.executeWithoutResult(tx -> {
            var pipeline = lock(pipelineId);
            if (pipeline == null || !PipelineStatus.COMPLETED.equals(pipeline.getStatus())) throw new IllegalStateException("Pipeline is not completed");
            lifecycle.requireActive(JsonUtil.fromJson(pipeline.getInputJson(), Input.class).media());
            var manifest = JsonUtil.fromJson(pipeline.getManifestJson(), Manifest.class);
            MediaTask row;
            try { row = insert(pipeline.getContentId(), pipelineId, "REVIEW", MediaTaskType.REVIEW, manifest.input()); }
            catch (DuplicateKeyException ignored) {
                log.debug("Review already registered pipelineId={}", pipelineId);
                return;
            }
            int attempt = row.getAttempt();
            afterCommit(() -> log.info("Review task accepted pipelineId={} contentId={} attempt={}", pipelineId, pipeline.getContentId(), attempt));
            ready(row, manifest.results(), manifest.input());
        });
    }
    private MediaTask insert(Long owner, Long pipelineId, String phase, MediaTaskType type, Input input) {
        var row = new MediaTask().setOwnerId(owner).setPipelineId(pipelineId).setPhase(phase).setTaskType(type.name())
                .setStatus(MediaTaskStatus.PENDING).setAttempt(0).setRetryCount(0).setCompletionAck(false);
        row.setDraftInputJson(pipelineId == null ? JsonUtil.toJson(input) : null).setDependenciesJson("{}");
        tasks.insert(row);
        return row;
    }
    public MediaTaskMessage claim(MediaTaskMessage message) {
        var manualCandidate = retryBatches == null ? null : tasks.selectById(message.getTaskId());
        String ownerReason = manualCandidate != null && manualCandidate.getRetryBatchId() != null && retryOwners != null
                ? retryOwners.reason(manualCandidate) : null;
        return transaction.execute(tx -> {
            if (manualCandidate != null && manualCandidate.getRetryBatchId() != null)
                retryBatches.lock(manualCandidate.getRetryBatchId());
            var pipeline = message.getPipelineId() == null ? null : lock(message.getPipelineId());
            try {
                if (pipeline != null) lifecycle.requireActive(JsonUtil.fromJson(pipeline.getInputJson(), Input.class).media());
                else {
                    var candidate = tasks.selectById(message.getTaskId());
                    if (candidate == null) {
                        log.debug("Task claim skipped taskId={} reason=TASK_MISSING", message.getTaskId());
                        return null;
                    }
                    if ("PREPARE".equals(candidate.getTaskType())) lifecycle.requireActive(candidate.getOwnerId());
                }
            } catch (IllegalStateException retired) {
                log.debug("Task claim skipped taskId={} reason=OWNER_RETIRED", message.getTaskId());
                if (manualCandidate != null && manualCandidate.getRetryBatchId() != null) {
                    var row=tasks.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getId,message.getTaskId()).last("FOR UPDATE"));
                    if (row!=null && MediaTaskStatus.READY.equals(row.getStatus()) && Objects.equals(row.getAttempt(),message.getAttempt()))
                        skipRetryRow(row,pipeline,"OWNER_RETIRED");
                }
                return null;
            }
            var row = tasks.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getId, message.getTaskId()).last("FOR UPDATE"));
            if (row == null || !Objects.equals(row.getPipelineId(), message.getPipelineId())
                    || !Objects.equals(row.getPhase(), message.getPhase()) || !MediaTaskStatus.READY.equals(row.getStatus())
                    || !Objects.equals(row.getAttempt(), message.getAttempt())) {
                metrics.counter("media.claim.skipped", "phase", row == null ? "UNKNOWN" : row.getPhase()).increment();
                log.debug("Task claim skipped taskId={} reason={} messageAttempt={}", message.getTaskId(), skipReason(row, message), message.getAttempt());
                return null;
            }
            if (row.getRetryBatchId() != null) {
                if (manualCandidate == null || !Objects.equals(row.getRetryBatchId(),manualCandidate.getRetryBatchId())
                        || !retryBatches.permit(row.getRetryBatchId())) return null;
            }
            if (row.getRetryBatchId() != null && ownerReason != null) {
                skipRetryRow(row,pipeline,ownerReason);
                return null;
            }
            var now = LocalDateTime.now(ZoneOffset.UTC);
            if (row.getUnavailableDeadline() != null && !now.isBefore(row.getUnavailableDeadline())) {
                unavailableExpired(row, input(row, pipeline));
                if ("CONTENT".equals(row.getPhase())) advance(pipeline, listPipeline(row.getPipelineId(), "CONTENT"));
                else notifyResult(row);
                if (retryBatches != null) retryBatches.taskChanged(row.getRetryBatchId());
                return null;
            }
            int nextAttempt = row.getAttempt() + 1;
            int claimed = tasks.update(null,new LambdaUpdateWrapper<MediaTask>().eq(MediaTask::getId,row.getId())
                    .eq(MediaTask::getStatus,MediaTaskStatus.READY).eq(MediaTask::getAttempt,row.getAttempt())
                    .set(MediaTask::getStatus,MediaTaskStatus.RUNNING).set(MediaTask::getAttempt,nextAttempt)
                    .set(MediaTask::getStartedAt,now).set(MediaTask::getUpdateTime,now));
            if (claimed == 0) {
                log.debug("Task claim skipped taskId={} reason=CLAIM_LOST", message.getTaskId());
                return null;
            }
            row.setStatus(MediaTaskStatus.RUNNING).setAttempt(nextAttempt).setStartedAt(now).setUpdateTime(now);
            if (retryBatches != null) retryBatches.taskChanged(row.getRetryBatchId());
            var input = input(row, pipeline);
            eventJournal.task(row, MediaTaskStatus.READY, row.getTaskType() + "_STARTED", input, null);
            afterCommit(() -> metrics.timer("media.node.wait", "type", row.getTaskType())
                    .record(Math.max(0, Duration.between(row.getReadyAt(), now).toMillis()), TimeUnit.MILLISECONDS));
            afterCommit(() -> log.info("Task claimed taskId={} pipelineId={} batchId={} type={} attempt={}",
                    row.getId(),row.getPipelineId(),row.getRetryBatchId(),row.getTaskType(),row.getAttempt()));
            return message(row, input);
        });
    }
    private void skipRetryRow(MediaTask row, Pipeline pipeline, String reason) {
        row.setStatus(MediaTaskStatus.BLOCKED).setFailureCode("RETRY_SKIPPED_"+reason).setErrorMessage(reason)
                .setNextRetryAt(null).setCompletionAck(false).setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        eventJournal.task(row,MediaTaskStatus.READY,row.getTaskType()+"_BLOCKED",input(row,pipeline),null);
        save(row);
        afterCommit(() -> log.info("Retry owner changed batchId={} taskId={} reason={}",row.getRetryBatchId(),row.getId(),reason));
        if ("CONTENT".equals(row.getPhase())) advance(pipeline,listPipeline(row.getPipelineId(),"CONTENT"));
        if (retryBatches != null) retryBatches.taskChanged(row.getRetryBatchId());
    }

    public void completeAndAdvance(MediaTaskMessage message, String result, String error) {
        completeAndAdvance(message, result, error, null, null);
    }
    public void completeModelFailure(MediaTaskMessage message, String resource, String error) {
        completeAndAdvance(message,null,error,null,resource);
    }
    public void deferAndAdvance(MediaTaskMessage message, ModelUnavailable unavailable) {
        completeAndAdvance(message, null, null, Objects.requireNonNull(unavailable), null);
    }
    private void completeAndAdvance(MediaTaskMessage message, String result, String error,
                                    ModelUnavailable unavailable, String resource) {
        transaction.executeWithoutResult(tx -> {
            var pipeline = message.getPipelineId() == null ? null : lock(message.getPipelineId());
            var row = tasks.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getId, message.getTaskId()).last("FOR UPDATE"));
            if (row == null || !Objects.equals(row.getPipelineId(), message.getPipelineId())
                    || !MediaTaskStatus.RUNNING.equals(row.getStatus()) || !Objects.equals(row.getAttempt(), message.getAttempt())) return;
            if (resource != null) row.setDependencyService(resource);
            if (unavailable == null) finish(row, result, error, input(row, pipeline));
            else defer(row, unavailable, input(row, pipeline));
            if ("CONTENT".equals(row.getPhase())) advance(pipeline, listPipeline(row.getPipelineId(), "CONTENT"));
            else notifyResult(row);
            if (retryBatches != null) retryBatches.taskChanged(row.getRetryBatchId());
        });
    }
    // 认领被拒的原因: 重复投递、迟到消息、已被其他 worker 抢走都会落到这里
    private static String skipReason(MediaTask row, MediaTaskMessage message) {
        if (row == null) return "TASK_MISSING";
        if (!Objects.equals(row.getPipelineId(), message.getPipelineId())) return "PIPELINE_MISMATCH";
        if (!Objects.equals(row.getPhase(), message.getPhase())) return "PHASE_MISMATCH";
        if (!MediaTaskStatus.READY.equals(row.getStatus())) return "STATUS_" + row.getStatus();
        return "ATTEMPT_MISMATCH";
    }
    private void finish(MediaTask row, String result, String error, Input input) {
        MediaTaskStatus from = row.getStatus();
        if (error == null) {
            if (result == null) throw new IllegalArgumentException("Missing task output");
            StructuredJson.object(result);
            row.setFailureCode(null).setDependencyService(null);
            row.setStatus(MediaTaskStatus.SUCCESS).setResultJson(result).setErrorMessage(null).setNextRetryAt(null).setUnavailableDeadline(null);
        } else {
            row.setFailureCode("Worker lease expired".equals(error) ? "TASK_LEASE_EXPIRED" : "TASK_EXECUTION_FAILED");
            row.setErrorMessage(error.substring(0, Math.min(500, error.length())));
            if (row.getRetryCount() >= 3) {
                row.setStatus(MediaTaskStatus.DEAD).setNextRetryAt(null);
                log.error("Task retries exhausted, manual retry required taskType={} taskId={} pipelineId={} attempt={} retryCount={} error={}",
                        row.getTaskType(), row.getId(), row.getPipelineId(), row.getAttempt(), row.getRetryCount(), row.getErrorMessage());
            } else {
                int retry = row.getRetryCount();
                long backoffSeconds = new long[]{30,120,600}[retry];
                row.setRetryCount(retry + 1).setStatus(MediaTaskStatus.RETRY_WAIT)
                        .setNextRetryAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(backoffSeconds));
                log.debug("Task retry scheduled taskType={} taskId={} pipelineId={} attempt={} nextRetryCount={} backoffSeconds={} error={}",
                        row.getTaskType(), row.getId(), row.getPipelineId(), row.getAttempt(), row.getRetryCount(), backoffSeconds, row.getErrorMessage());
            }
        }
        row.setCompletionAck(false).setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        eventJournal.task(row, from, row.getTaskType() + (error == null ? "_SUCCEEDED" : "_FAILED"), input,
                error == null ? null : "Worker lease expired".equals(error) ? "TASK_LEASE_EXPIRED" : "TASK_EXECUTION_FAILED");
        save(row);
    }
    private void defer(MediaTask row, ModelUnavailable unavailable, Input input) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        row.setFailureCode(unavailable.code()).setDependencyService(unavailable.resource());
        if (unavailable.terminal()) {
            MediaTaskStatus from = row.getStatus();
            row.setStatus(MediaTaskStatus.DEAD).setNextRetryAt(null).setUnavailableDeadline(null)
                    .setErrorMessage(unavailable.code()).setCompletionAck(false).setUpdateTime(now);
            eventJournal.task(row, from, row.getTaskType() + "_FAILED", input, unavailable.code());
            save(row);
            afterCommit(() -> {
                log.warn("Task stopped for manual retry taskId={} pipelineId={} batchId={} attempt={} resource={} reason={}",
                        row.getId(), row.getPipelineId(), row.getRetryBatchId(), row.getAttempt(), unavailable.resource(), unavailable.code());
                metrics.counter("media.model.terminal", "type", row.getTaskType(), "reason", unavailable.code()).increment();
            });
            return;
        }
        if (row.getUnavailableDeadline() == null)
            row.setUnavailableDeadline(now.plusSeconds(properties.getModelUnavailableWaitSeconds()));
        row.setCompletionAck(false).setUpdateTime(now).setErrorMessage(unavailable.code());
        if (!now.isBefore(row.getUnavailableDeadline())) {
            unavailableExpired(row, input);
            return;
        }
        var retryAt = now.plusSeconds(unavailable.retryAfterSeconds() + java.util.concurrent.ThreadLocalRandom.current().nextInt(6));
        row.setStatus(MediaTaskStatus.RETRY_WAIT).setNextRetryAt(retryAt.isAfter(row.getUnavailableDeadline()) ? row.getUnavailableDeadline() : retryAt);
        eventJournal.task(row, MediaTaskStatus.RUNNING, row.getTaskType() + "_DEFERRED", input, unavailable.code());
        save(row);
        afterCommit(() -> log.info("Task deferred taskId={} pipelineId={} batchId={} attempt={} resource={} reason={} retryAt={} deadline={}",
                row.getId(), row.getPipelineId(), row.getRetryBatchId(), row.getAttempt(), unavailable.resource(), unavailable.code(), row.getNextRetryAt(), row.getUnavailableDeadline()));
        afterCommit(() -> metrics.counter("media.model.deferred", "type", row.getTaskType(), "reason", unavailable.code()).increment());
    }
    private void unavailableExpired(MediaTask row, Input input) {
        MediaTaskStatus from = row.getStatus();
        row.setFailureCode("MODEL_UNAVAILABLE_TIMEOUT");
        row.setStatus(MediaTaskStatus.DEAD).setNextRetryAt(null).setErrorMessage("MODEL_UNAVAILABLE_TIMEOUT")
                .setCompletionAck(false).setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        eventJournal.task(row, from, row.getTaskType() + "_FAILED", input, "MODEL_UNAVAILABLE_TIMEOUT");
        save(row);
        afterCommit(() -> metrics.counter("media.model.unavailable.exhausted", "type", row.getTaskType()).increment());
        log.error("Model unavailable deadline exceeded taskType={} taskId={} attempt={}", row.getTaskType(), row.getId(), row.getAttempt());
    }
    private void advance(Pipeline pipeline, List<MediaTask> nodes) {
        advance(pipeline, nodes, Map.of());
    }
    private void advance(Pipeline pipeline, List<MediaTask> nodes, Map<Long, MediaTaskStatus> resumed) {
        if (pipeline == null || PipelineStatus.COMPLETED.equals(pipeline.getStatus())) return;
        var input = JsonUtil.fromJson(pipeline.getInputJson(), Input.class);
        Map<String, MediaTask> byType = nodes.stream().collect(Collectors.toMap(MediaTask::getTaskType, t -> t));
        boolean changed;
        do {
            changed = false;
            for (var row : nodes) {
                if (!MediaTaskStatus.PENDING.equals(row.getStatus())) continue;
                var deps = MediaTaskType.fromCode(row.getTaskType()).getDependencies();
                if (deps.stream().anyMatch(d -> Set.of(MediaTaskStatus.DEAD, MediaTaskStatus.BLOCKED).contains(byType.get(d).getStatus()))) {
                    String upstream = deps.stream().filter(d -> Set.of(MediaTaskStatus.DEAD, MediaTaskStatus.BLOCKED).contains(byType.get(d).getStatus()))
                            .collect(Collectors.joining(","));
                    log.warn("Task blocked by upstream failure taskType={} taskId={} pipelineId={} upstream={}",
                            row.getTaskType(), row.getId(), row.getPipelineId(), upstream);
                    row.setStatus(MediaTaskStatus.BLOCKED).setErrorMessage("Upstream terminal failure");
                    eventJournal.task(row, resumed.getOrDefault(row.getId(), MediaTaskStatus.PENDING), row.getTaskType() + "_BLOCKED", input, null);
                    save(row); changed = true;
                } else if (deps.stream().allMatch(d -> MediaTaskStatus.SUCCESS.equals(byType.get(d).getStatus()))) {
                    log.debug("Task released taskType={} taskId={} pipelineId={} dependencies={}",
                            row.getTaskType(), row.getId(), row.getPipelineId(), deps);
                    ready(row, deps.stream().collect(Collectors.toMap(d -> d, d -> byType.get(d).getResultJson())), input,
                            resumed.getOrDefault(row.getId(), row.getStatus()), resumed.containsKey(row.getId())); changed = true;
                }
            }
        } while (changed);
        PipelineStatus status;
        try { status = PipelineState.derive(nodes); }
        catch (IllegalStateException e) {
            metrics.counter("media.pipeline.graph.errors").increment();
            log.error("Pipeline dependency anomaly pipelineId={}", pipeline.getId(), e);
            pipelines.update(null,new LambdaUpdateWrapper<Pipeline>().eq(Pipeline::getId,pipeline.getId()).set(Pipeline::getStatus,PipelineStatus.PROCESSING)); return;
        }
        PipelineStatus previous = pipeline.getStatus();
        pipeline.setStatus(status);
        if (!status.equals(previous)) eventJournal.pipeline(pipeline, previous,
                PipelineStatus.COMPLETED.equals(status) ? "PIPELINE_COMPLETED" : PipelineStatus.WAITING_ARTIFACTS.equals(status) ? "PIPELINE_WAITING_ARTIFACTS" : "PIPELINE_RESUMED", input);
        if (PipelineStatus.COMPLETED.equals(status)) {
            var now = LocalDateTime.now(ZoneOffset.UTC);
            var manifest = new Manifest(pipeline.getId(), pipeline.getContentId(), status, input,
                    nodes.stream().collect(Collectors.toMap(MediaTask::getTaskType, MediaTask::getResultJson)),
                    now.toInstant(ZoneOffset.UTC).toEpochMilli());
            pipeline.setCompletedAt(now).setManifestJson(JsonUtil.toJson(manifest)).setNotifyAfter(now.plusMinutes(5));
            afterCommit(() -> {
                long elapsedMs = Math.max(0, manifest.completedAt() - input.submittedAt());
                metrics.timer("media.pipeline.duration").record(elapsedMs, TimeUnit.MILLISECONDS);
                log.info("Pipeline completed pipelineId={} contentId={} media={} nodes={} elapsedMs={}",
                        pipeline.getId(), pipeline.getContentId(), input.media().type(), nodes.size(), elapsedMs);
                dispatcher.completed(manifest);
            });
        }
        pipelines.updateById(pipeline);
    }
    private void ready(MediaTask row, Map<String, String> deps, Input input) {
        ready(row, deps, input, row.getStatus(), MediaTaskStatus.DEAD.equals(row.getStatus()));
    }
    private void ready(MediaTask row, Map<String, String> deps, Input input, MediaTaskStatus from, boolean manual) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        row.setDependenciesJson(JsonUtil.toJson(deps)).setStatus(MediaTaskStatus.READY).setReadyAt(now)
                .setNextRetryAt(null).setDispatchAfter(now.plusSeconds(30));
        eventJournal.task(row, from, row.getTaskType() + (manual ? "_RETRY_REQUESTED" : "_READY"), input, null);
        save(row);
        var execution = message(row, input);
        afterCommit(() -> dispatcher.dispatch(execution));
    }
    private Input input(MediaTask row, Pipeline pipeline) {
        if (MediaTaskType.AUDIO_PREPARE.matches(row.getTaskType())) return null;
        return JsonUtil.fromJson(pipeline == null ? row.getDraftInputJson() : pipeline.getInputJson(), Input.class);
    }
    private Map<String, String> dependencies(MediaTask row) {
        return JsonUtil.GSON.fromJson(row.getDependenciesJson(),
                com.google.gson.reflect.TypeToken.getParameterized(Map.class, String.class, String.class).getType());
    }
    private MediaTaskMessage message(MediaTask row, Input input) {
        if (MediaTaskType.AUDIO_PREPARE.matches(row.getTaskType())) {
            var audio = JsonUtil.fromJson(row.getDraftInputJson(), AudioPreparationContracts.Request.class);
            return MediaTaskMessage.builder().taskId(row.getId()).ownerId(row.getOwnerId()).phase(row.getPhase())
                    .taskType(row.getTaskType()).attempt(row.getAttempt()).userId(audio.userId()).audioInput(audio).build();
        }
        return MediaTaskMessage.builder().taskId(row.getId()).ownerId(row.getOwnerId()).pipelineId(row.getPipelineId())
                .retryBatchId(row.getRetryBatchId()).phase(row.getPhase()).taskType(row.getTaskType()).attempt(row.getAttempt())
                .userId(input.userId()).objectKey(input.media().video() == null ? null : input.media().video().objectKey()).input(input).dependencies(dependencies(row)).build();
    }
    private void notifyResult(MediaTask row) {
        if ("DRAFT".equals(row.getPhase()) && Set.of(MediaTaskType.PREPARE.name(), MediaTaskType.AUDIO_PREPARE.name()).contains(row.getTaskType())) {
            if (Set.of(MediaTaskStatus.SUCCESS, MediaTaskStatus.DEAD).contains(row.getStatus())) {
                row.setCompletionAck(true); save(row);
            }
        } else if ("REVIEW".equals(row.getPhase())) {
            var result = new ReviewResult(row.getPipelineId(), row.getOwnerId(), row.getId(), row.getAttempt(),
                    row.getResultJson(), row.getErrorMessage(), MediaTaskStatus.DEAD.equals(row.getStatus()));
            afterCommit(() -> dispatcher.reviewed(result));
        }
    }
    public boolean retry(Long id) {
        if (retryBatches != null) listPipeline(id, "CONTENT").stream().map(MediaTask::getRetryBatchId)
                .filter(Objects::nonNull).distinct().forEach(retryBatches::batch);
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var pipeline = lock(id);
            if (pipeline == null || PipelineStatus.COMPLETED.equals(pipeline.getStatus())) {
                log.debug("Pipeline retry rejected pipelineId={} reason={}", id, pipeline == null ? "PIPELINE_MISSING" : "ALREADY_COMPLETED");
                return false;
            }
            lifecycle.requireActive(JsonUtil.fromJson(pipeline.getInputJson(), Input.class).media());
            if (PipelineStatus.PROCESSING.equals(pipeline.getStatus())) {
                log.debug("Pipeline retry no-op pipelineId={} reason=STILL_PROCESSING", id);
                return true;
            }
            var nodes = listPipeline(id, "CONTENT");
            if (retryBatches != null && nodes.stream().anyMatch(row -> row.getRetryBatchId() != null
                    && Set.of(MediaRetryBatchStatus.QUEUED, MediaRetryBatchStatus.RUNNING).contains(retryBatches.status(row.getRetryBatchId())))) return false;
            var resumed = new HashMap<Long, MediaTaskStatus>();
            for (var row : nodes) if (Set.of(MediaTaskStatus.DEAD, MediaTaskStatus.BLOCKED).contains(row.getStatus())) {
                resumed.put(row.getId(), row.getStatus());
                row.setRetryBatchId(null).setFailureCode(null).setDependencyService(null);
                row.setStatus(MediaTaskStatus.PENDING).setRetryCount(0).setErrorMessage(null).setCompletionAck(false)
                        .setNextRetryAt(null).setUnavailableDeadline(null);
            }
            advance(pipeline, nodes, resumed);
            var input = JsonUtil.fromJson(pipeline.getInputJson(), Input.class);
            for (var row : nodes) if (resumed.containsKey(row.getId()) && MediaTaskStatus.PENDING.equals(row.getStatus())) {
                eventJournal.task(row, resumed.get(row.getId()), row.getTaskType() + "_RETRY_REQUESTED", input, null); save(row);
            }
            String resumedTasks = nodes.stream().filter(n -> resumed.containsKey(n.getId()))
                    .map(n -> n.getTaskType() + ":" + resumed.get(n.getId())).collect(Collectors.joining(","));
            afterCommit(() -> log.info("Pipeline retry accepted pipelineId={} contentId={} resumedTasks={}",
                    pipeline.getId(), pipeline.getContentId(), resumedTasks.isEmpty() ? "none" : resumedTasks));
            return true;
        }));
    }
    public Input retryInput(MediaTask row) {
        return input(row,row.getPipelineId()==null ? null : pipelines.selectById(row.getPipelineId()));
    }
    public Map<Long, String> resumeSelected(Long batchId, Map<Long,Integer> selected) {
        var skipped = new HashMap<Long, String>();
        if (selected.isEmpty()) return skipped;
        var candidates = tasks.selectBatchIds(selected.keySet());
        var present = candidates.stream().map(MediaTask::getId).collect(Collectors.toSet());
        selected.keySet().stream().filter(id -> !present.contains(id)).forEach(id -> skipped.put(id, "TASK_MISSING"));
        var groups=candidates.stream().collect(Collectors.groupingBy(row -> row.getPhase()+":"+
                (row.getPipelineId()==null ? row.getOwnerId() : row.getPipelineId())));
        for (var group : groups.values()) {
            var first=group.get(0);
            var pipeline=first.getPipelineId()==null ? null : lock(first.getPipelineId());
            if (first.getPipelineId()!=null && pipeline==null) {
                for (var row:group) skipped.put(row.getId(),"PIPELINE_MISSING");
                continue;
            }
            if (pipeline==null && group.stream().anyMatch(row -> "PREPARE".equals(row.getTaskType()))) {
                try { lifecycle.requireActive(first.getOwnerId()); }
                catch (IllegalStateException retired) {
                    for (var row:group) skipped.put(row.getId(),"OWNER_RETIRED");
                    continue;
                }
            }
            var nodes=pipeline==null ? tasks.selectList(new LambdaQueryWrapper<MediaTask>().in(MediaTask::getId,
                    group.stream().map(MediaTask::getId).toList()).orderByAsc(MediaTask::getId).last("FOR UPDATE"))
                    : listPipeline(pipeline.getId(),first.getPhase());
            var byType=nodes.stream().collect(Collectors.toMap(MediaTask::getTaskType,row -> row));
            var resumed=new HashMap<Long,MediaTaskStatus>();
            var excluded=new HashSet<Long>();
            for (var row : nodes) if (selected.containsKey(row.getId())) {
                if (!Objects.equals(row.getAttempt(),selected.get(row.getId())) || !Set.of(MediaTaskStatus.DEAD,MediaTaskStatus.BLOCKED).contains(row.getStatus())) {
                    skipped.put(row.getId(),"TASK_STATE_CHANGED"); excluded.add(row.getId()); continue;
                }
                if (MediaTaskStatus.DEAD.equals(row.getStatus())) resumed.put(row.getId(),row.getStatus());
            }
            boolean changed;
            do {
                changed=false;
                for (var row : nodes) if (MediaTaskStatus.BLOCKED.equals(row.getStatus()) && !resumed.containsKey(row.getId()) && !excluded.contains(row.getId())) {
                    var deps=MediaTaskType.fromCode(row.getTaskType()).getDependencies();
                    if (!deps.isEmpty() && deps.stream().allMatch(d -> MediaTaskStatus.SUCCESS.equals(byType.get(d).getStatus()) || resumed.containsKey(byType.get(d).getId()))
                            && (selected.containsKey(row.getId()) || deps.stream().anyMatch(d -> resumed.containsKey(byType.get(d).getId())))) {
                        resumed.put(row.getId(),MediaTaskStatus.BLOCKED); changed=true;
                    }
                }
            } while (changed);
            for (var row : nodes) if (selected.containsKey(row.getId()) && !resumed.containsKey(row.getId()))
                skipped.put(row.getId(),MediaTaskStatus.BLOCKED.equals(row.getStatus()) ? "DEPENDENCY_NOT_SELECTED" : "TASK_STATE_CHANGED");
            if (resumed.isEmpty()) continue;
            if (pipeline!=null) {
                try { lifecycle.requireActive(JsonUtil.fromJson(pipeline.getInputJson(),Input.class).media()); }
                catch (IllegalStateException retired) {
                    for (var row:group) skipped.put(row.getId(),"OWNER_RETIRED");
                    continue;
                }
            }
            for (var row : nodes) if (resumed.containsKey(row.getId())) {
                row.setRetryBatchId(batchId).setStatus(MediaTaskStatus.PENDING).setRetryCount(0).setFailureCode(null).setDependencyService(null)
                        .setErrorMessage(null).setCompletionAck(false).setNextRetryAt(null).setUnavailableDeadline(null);
                eventJournal.task(row,resumed.get(row.getId()),row.getTaskType()+"_RETRY_REQUESTED",input(row,pipeline),null);
                save(row);
                afterCommit(() -> log.info("Retry task resumed batchId={} taskId={} pipelineId={} type={} attempt={}",
                        batchId,row.getId(),row.getPipelineId(),row.getTaskType(),row.getAttempt()));
            }
            if ("CONTENT".equals(first.getPhase())) advance(pipeline,nodes);
            else for (var row : nodes) if (resumed.containsKey(row.getId())) ready(row,dependencies(row),input(row,pipeline));
        }
        return skipped;
    }
    public void heartbeat(Long taskId, int attempt) {
        if (tasks.update(null, new LambdaUpdateWrapper<MediaTask>().eq(MediaTask::getId, taskId)
                .eq(MediaTask::getStatus, MediaTaskStatus.RUNNING).eq(MediaTask::getAttempt, attempt)
                .set(MediaTask::getUpdateTime, LocalDateTime.now(ZoneOffset.UTC))) == 0)
            throw new IllegalStateException("Task lease lost");
    }
    public void received(Long pipelineId) {
        pipelines.update(null, new LambdaUpdateWrapper<Pipeline>().eq(Pipeline::getId, pipelineId).eq(Pipeline::getStatus, PipelineStatus.COMPLETED)
                .set(Pipeline::getReceived, true));
    }
    public void resultReceived(Long id, int attempt) {
        var ack = new TransactionTemplate(transaction.getTransactionManager());
        ack.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ack.executeWithoutResult(tx -> tasks.update(null, new LambdaUpdateWrapper<MediaTask>().eq(MediaTask::getId, id).eq(MediaTask::getAttempt, attempt)
                .set(MediaTask::getCompletionAck, true)));
    }
    public List<MediaTask> listByDraft(Long id) {
        return tasks.selectList(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getOwnerId, id).eq(MediaTask::getPhase, "DRAFT"));
    }
    public List<MediaTask> listPipeline(Long id, String phase) {
        return tasks.selectList(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getPipelineId, id).eq(MediaTask::getPhase, phase).orderByAsc(MediaTask::getId));
    }
    public MediaTaskPort.PipelineView view(Long id) {
        var pipeline = pipelines.selectById(id);
        if (pipeline == null) return null;
        return view(pipeline);
    }
    private MediaTaskPort.PipelineView view(Pipeline pipeline) {
        var rows = tasks.selectList(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getPipelineId, pipeline.getId()).orderByAsc(MediaTask::getId));
        return new MediaTaskPort.PipelineView(pipeline.getId(), pipeline.getContentId(), pipeline.getStatus(), rows.stream()
                .map(t -> new MediaTaskPort.Node(t.getId(), t.getTaskType(), t.getPhase(), t.getStatus(), t.getAttempt(), t.getRetryCount(), t.getResultJson(), t.getErrorMessage())).toList());
    }
    public List<MediaTaskPort.PipelineView> waiting(int page, int size) {
        int limit = Math.min(100, Math.max(1, size)), offset = (Math.max(1, page) - 1) * limit;
        return pipelines.selectList(new LambdaQueryWrapper<Pipeline>().eq(Pipeline::getStatus, PipelineStatus.WAITING_ARTIFACTS)
                .orderByAsc(Pipeline::getId).last("LIMIT " + limit + " OFFSET " + offset)).stream().map(this::view).toList();
    }
    public void recover() {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        long cursor = 0;
        while (true) {
            var page = tasks.selectList(new LambdaQueryWrapper<MediaTask>().gt(MediaTask::getId, cursor)
                    .and(q -> q.eq(MediaTask::getStatus, MediaTaskStatus.READY).le(MediaTask::getDispatchAfter, now)
                            .or().eq(MediaTask::getStatus, MediaTaskStatus.RETRY_WAIT).le(MediaTask::getNextRetryAt, now)
                            .or().eq(MediaTask::getStatus, MediaTaskStatus.RUNNING).lt(MediaTask::getUpdateTime, now.minusSeconds(properties.getLeaseSeconds()))
                            .or().eq(MediaTask::getPhase, "DRAFT").in(MediaTask::getStatus, MediaTaskStatus.SUCCESS, MediaTaskStatus.DEAD).eq(MediaTask::getCompletionAck, false)
                            .or().eq(MediaTask::getPhase, "REVIEW").in(MediaTask::getStatus, MediaTaskStatus.SUCCESS, MediaTaskStatus.DEAD).eq(MediaTask::getCompletionAck, false))
                    .orderByAsc(MediaTask::getId).last("LIMIT 100"));
            if (page.isEmpty()) break;
            cursor = page.get(page.size()-1).getId();
            var groups = page.stream().collect(Collectors.groupingBy(t -> t.getPhase() + ":" + (t.getPipelineId() == null ? t.getOwnerId() : t.getPipelineId())));
            for (var group : groups.values()) {
                try { transaction.executeWithoutResult(tx -> recover(group, now)); }
                catch (Exception e) { log.error("Task recovery failed taskId={}", group.get(0).getId(), e); }
            }
        }
    }
    private void recover(List<MediaTask> candidates, LocalDateTime now) {
        var first = candidates.get(0);
        var pipeline = first.getPipelineId() == null ? null : lock(first.getPipelineId());
        var rows = first.getPipelineId() == null ? tasks.selectList(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getId, first.getId()).last("FOR UPDATE"))
                : listPipeline(first.getPipelineId(), first.getPhase());
        var input = input(first, pipeline);
        boolean graphChanged = false;
        var changedBatches = new HashSet<Long>();
        for (var row : rows) {
            if (MediaTaskStatus.RUNNING.equals(row.getStatus()) && row.getUpdateTime().isBefore(now.minusSeconds(properties.getLeaseSeconds()))) {
                log.warn("Task lease expired, requeued taskType={} taskId={} pipelineId={} attempt={} leaseSeconds={}",
                        row.getTaskType(), row.getId(), row.getPipelineId(), row.getAttempt(), properties.getLeaseSeconds());
                finish(row, null, "Worker lease expired", input); graphChanged = true;
                changedBatches.add(row.getRetryBatchId());
                if (!"CONTENT".equals(row.getPhase())) notifyResult(row);
            } else if (MediaTaskStatus.RETRY_WAIT.equals(row.getStatus()) && !row.getNextRetryAt().isAfter(now)) {
                if (row.getUnavailableDeadline() != null && !now.isBefore(row.getUnavailableDeadline())) {
                    unavailableExpired(row, input); graphChanged = true;
                    changedBatches.add(row.getRetryBatchId());
                    if (!"CONTENT".equals(row.getPhase())) notifyResult(row);
                    continue;
                }
                metrics.timer("media.node.retry.wait", "type", row.getTaskType()).record(Math.max(0, Duration.between(row.getUpdateTime(), now).toMillis()), TimeUnit.MILLISECONDS);
                ready(row, dependencies(row), input);
                changedBatches.add(row.getRetryBatchId());
            } else if (MediaTaskStatus.READY.equals(row.getStatus()) && !row.getDispatchAfter().isAfter(now)) {
                log.debug("Task redispatch taskType={} taskId={} pipelineId={} attempt={}", row.getTaskType(), row.getId(), row.getPipelineId(), row.getAttempt());
                row.setDispatchAfter(now.plusSeconds(30)); save(row);
                var execution = message(row, input);
                afterCommit(() -> dispatcher.dispatch(execution));
            } else if (!Boolean.TRUE.equals(row.getCompletionAck()) && Set.of(MediaTaskStatus.SUCCESS, MediaTaskStatus.DEAD).contains(row.getStatus())) notifyResult(row);
        }
        if (graphChanged && "CONTENT".equals(first.getPhase())) advance(pipeline, rows);
        if (retryBatches != null) changedBatches.stream().filter(Objects::nonNull).forEach(retryBatches::taskChanged);
    }
    public void recoverCompletions() {
        long cursor = 0;
        var now = LocalDateTime.now(ZoneOffset.UTC);
        while (true) {
            var page = pipelines.selectList(new LambdaQueryWrapper<Pipeline>().gt(Pipeline::getId, cursor)
                    .eq(Pipeline::getStatus, PipelineStatus.COMPLETED).eq(Pipeline::getReceived, false).le(Pipeline::getNotifyAfter, now)
                    .orderByAsc(Pipeline::getId).last("LIMIT 100"));
            if (page.isEmpty()) break;
            cursor = page.get(page.size()-1).getId();
            for (var p : page) try {
                log.warn("Pipeline completion re-delivered, previous delivery not acknowledged pipelineId={}", p.getId());
                dispatcher.completed(JsonUtil.fromJson(p.getManifestJson(), Manifest.class));
                pipelines.update(null, new LambdaUpdateWrapper<Pipeline>().eq(Pipeline::getId, p.getId()).set(Pipeline::getNotifyAfter, now.plusMinutes(5)));
            } catch (Exception e) { log.error("Pipeline completion delivery failed pipelineId={}", p.getId(), e); }
        }
    }
    private Pipeline lock(Long id) { return pipelines.selectOne(new LambdaQueryWrapper<Pipeline>().eq(Pipeline::getId, id).last("FOR UPDATE")); }
    private void save(MediaTask row) { tasks.updateById(row); }
    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { action.run(); }
                catch (Exception e) { metrics.counter("media.delivery.failures").increment(); log.error("Post-commit delivery deferred to recovery", e); }
            }
        });
    }
}

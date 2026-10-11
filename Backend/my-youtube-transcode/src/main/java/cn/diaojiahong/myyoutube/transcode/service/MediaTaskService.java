package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.domain.po.*;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.transcode.api.MediaTaskPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final PipelineMapper pipelines;
    private final TransactionTemplate transaction;
    private final MediaTaskDispatcher dispatcher;
    private final PipelineProperties properties;
    private final MeterRegistry metrics;
    private final cn.diaojiahong.myyoutube.video.api.ContentPipelinePort video;

    public void start(Input input) {
        if (input == null || input.pipelineId() == null || input.contentId() == null || input.userId() == null
                || input.media() == null || input.submittedAt() <= 0)
            throw new IllegalArgumentException("Incomplete immutable Pipeline input");
        MediaInputs.validate(input.media(), input.coverObjectKey());
        transaction.executeWithoutResult(tx -> {
            var pipeline = new Pipeline().setId(input.pipelineId()).setContentId(input.contentId())
                    .setInputJson(JsonUtil.toJson(input)).setStatus("PROCESSING").setReceived(false)
                    .setSubmittedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(input.submittedAt()), ZoneOffset.UTC));
            try {
                pipelines.insert(pipeline);
            } catch (DuplicateKeyException e) {
                var current = pipelines.selectById(input.pipelineId());
                if (!input.equals(JsonUtil.fromJson(current.getInputJson(), Input.class))) throw new IllegalStateException("pipelineId input mismatch");
                return;
            }
            var nodes = new ArrayList<MediaTask>();
            for (var type : MediaTaskType.contentTypes()) nodes.add(insert(input.contentId(), input.pipelineId(), "CONTENT", type, input));
            advance(pipeline, nodes);
        });
    }
    public void startDraft(Long id, Long userId, String objectKey) {
        transaction.executeWithoutResult(tx -> {
            try {
                var input = new Input(null, id, userId, "", "", "", null, new ContentMedia("video", new VideoMedia(objectKey, 0), null), System.currentTimeMillis());
                var row = insert(id, null, "DRAFT", MediaTaskType.TRANSCODE, input);
                ready(row, Map.of(), input);
            } catch (DuplicateKeyException ignored) { /* Existing READY work is recovered by its dispatch deadline. */ }
        });
    }
    public void startReview(Long pipelineId) {
        if (!video.reviewSpec(pipelineId, 0).enabled()) return;
        transaction.executeWithoutResult(tx -> {
            var pipeline = lock(pipelineId);
            if (pipeline == null || !"COMPLETED".equals(pipeline.getStatus())) throw new IllegalStateException("Pipeline is not completed");
            try {
                var manifest = JsonUtil.fromJson(pipeline.getManifestJson(), Manifest.class);
                var row = insert(pipeline.getContentId(), pipelineId, "REVIEW", MediaTaskType.REVIEW, manifest.input());
                ready(row, manifest.results(), manifest.input());
            } catch (DuplicateKeyException ignored) { }
        });
    }
    private MediaTask insert(Long owner, Long pipelineId, String phase, MediaTaskType type, Input input) {
        var row = new MediaTask().setOwnerId(owner).setPipelineId(pipelineId).setPhase(phase).setTaskType(type.name())
                .setStatus("PENDING").setAttempt(0).setRetryCount(0).setCompletionAck(false);
        row.setDraftInputJson(pipelineId == null ? JsonUtil.toJson(input) : null).setDependenciesJson("{}");
        tasks.insert(row);
        return row;
    }
    public MediaTaskMessage claim(MediaTaskMessage message) {
        return transaction.execute(tx -> {
            var pipeline = message.getPipelineId() == null ? null : lock(message.getPipelineId());
            var row = tasks.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getId, message.getTaskId()).last("FOR UPDATE"));
            if (row == null || !Objects.equals(row.getPipelineId(), message.getPipelineId())
                    || !Objects.equals(row.getPhase(), message.getPhase()) || !"READY".equals(row.getStatus())
                    || !Objects.equals(row.getAttempt(), message.getAttempt())) {
                metrics.counter("media.claim.skipped", "phase", row == null ? "UNKNOWN" : row.getPhase()).increment(); return null;
            }
            var now = LocalDateTime.now(ZoneOffset.UTC);
            int nextAttempt = row.getAttempt() + 1;
            int claimed = tasks.update(null,new LambdaUpdateWrapper<MediaTask>().eq(MediaTask::getId,row.getId())
                    .eq(MediaTask::getStatus,"READY").eq(MediaTask::getAttempt,row.getAttempt())
                    .set(MediaTask::getStatus,"RUNNING").set(MediaTask::getAttempt,nextAttempt)
                    .set(MediaTask::getStartedAt,now).set(MediaTask::getUpdateTime,now));
            if (claimed == 0) return null;
            row.setStatus("RUNNING").setAttempt(nextAttempt).setStartedAt(now).setUpdateTime(now);
            afterCommit(() -> metrics.timer("media.node.wait", "type", row.getTaskType())
                    .record(Math.max(0, Duration.between(row.getReadyAt(), now).toMillis()), TimeUnit.MILLISECONDS));
            return message(row, input(row, pipeline));
        });
    }
    public void completeAndAdvance(MediaTaskMessage message, String result, String error) {
        transaction.executeWithoutResult(tx -> {
            var pipeline = message.getPipelineId() == null ? null : lock(message.getPipelineId());
            var row = tasks.selectOne(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getId, message.getTaskId()).last("FOR UPDATE"));
            if (row == null || !Objects.equals(row.getPipelineId(), message.getPipelineId())
                    || !"RUNNING".equals(row.getStatus()) || !Objects.equals(row.getAttempt(), message.getAttempt())) return;
            finish(row, result, error);
            if ("CONTENT".equals(row.getPhase())) advance(pipeline, listPipeline(row.getPipelineId(), "CONTENT"));
            else notifyResult(row);
        });
    }
    private void finish(MediaTask row, String result, String error) {
        if (error == null) {
            if (result == null) throw new IllegalArgumentException("Missing task output");
            StructuredJson.object(result);
            row.setStatus("SUCCESS").setResultJson(result).setErrorMessage(null);
        } else {
            row.setErrorMessage(error.substring(0, Math.min(500, error.length())));
            if (row.getRetryCount() >= 3) row.setStatus("DEAD");
            else {
                int retry = row.getRetryCount();
                row.setRetryCount(retry + 1).setStatus("RETRY_WAIT")
                        .setNextRetryAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(new long[]{30,120,600}[retry]));
            }
        }
        row.setCompletionAck(false).setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        save(row);
    }
    private void advance(Pipeline pipeline, List<MediaTask> nodes) {
        if (pipeline == null || "COMPLETED".equals(pipeline.getStatus())) return;
        var input = JsonUtil.fromJson(pipeline.getInputJson(), Input.class);
        Map<String, MediaTask> byType = nodes.stream().collect(Collectors.toMap(MediaTask::getTaskType, t -> t));
        boolean changed;
        do {
            changed = false;
            for (var row : nodes) {
                if (!"PENDING".equals(row.getStatus())) continue;
                var deps = MediaTaskType.fromCode(row.getTaskType()).getDependencies();
                if (deps.stream().anyMatch(d -> Set.of("DEAD", "BLOCKED").contains(byType.get(d).getStatus()))) {
                    row.setStatus("BLOCKED").setErrorMessage("Upstream terminal failure"); save(row); changed = true;
                } else if (deps.stream().allMatch(d -> "SUCCESS".equals(byType.get(d).getStatus()))) {
                    ready(row, deps.stream().collect(Collectors.toMap(d -> d, d -> byType.get(d).getResultJson())), input); changed = true;
                }
            }
        } while (changed);
        String status;
        try { status = PipelineState.derive(nodes); }
        catch (IllegalStateException e) {
            metrics.counter("media.pipeline.graph.errors").increment();
            log.error("Pipeline dependency anomaly pipelineId={}", pipeline.getId(), e);
            pipelines.update(null,new LambdaUpdateWrapper<Pipeline>().eq(Pipeline::getId,pipeline.getId()).set(Pipeline::getStatus,"PROCESSING")); return;
        }
        pipeline.setStatus(status);
        if ("COMPLETED".equals(status)) {
            var now = LocalDateTime.now(ZoneOffset.UTC);
            var manifest = new Manifest(pipeline.getId(), pipeline.getContentId(), status, input,
                    nodes.stream().collect(Collectors.toMap(MediaTask::getTaskType, MediaTask::getResultJson)),
                    now.toInstant(ZoneOffset.UTC).toEpochMilli());
            pipeline.setCompletedAt(now).setManifestJson(JsonUtil.toJson(manifest)).setNotifyAfter(now.plusMinutes(5));
            afterCommit(() -> {
                metrics.timer("media.pipeline.duration").record(Math.max(0, manifest.completedAt() - input.submittedAt()), TimeUnit.MILLISECONDS);
                dispatcher.completed(manifest);
            });
        }
        pipelines.updateById(pipeline);
    }
    private void ready(MediaTask row, Map<String, String> deps, Input input) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        row.setDependenciesJson(JsonUtil.toJson(deps)).setStatus("READY").setReadyAt(now)
                .setNextRetryAt(null).setDispatchAfter(now.plusSeconds(30));
        save(row);
        var execution = message(row, input);
        afterCommit(() -> dispatcher.dispatch(execution));
    }
    private Input input(MediaTask row, Pipeline pipeline) {
        return JsonUtil.fromJson(pipeline == null ? row.getDraftInputJson() : pipeline.getInputJson(), Input.class);
    }
    private Map<String, String> dependencies(MediaTask row) {
        return JsonUtil.GSON.fromJson(row.getDependenciesJson(),
                com.google.gson.reflect.TypeToken.getParameterized(Map.class, String.class, String.class).getType());
    }
    private MediaTaskMessage message(MediaTask row, Input input) {
        return MediaTaskMessage.builder().taskId(row.getId()).ownerId(row.getOwnerId()).pipelineId(row.getPipelineId())
                .phase(row.getPhase()).taskType(row.getTaskType()).attempt(row.getAttempt())
                .userId(input.userId()).objectKey(input.media().video() == null ? null : input.media().video().objectKey()).input(input).dependencies(dependencies(row)).build();
    }
    private void notifyResult(MediaTask row) {
        if ("REVIEW".equals(row.getPhase())) {
            var result = new ReviewResult(row.getPipelineId(), row.getOwnerId(), row.getId(), row.getAttempt(),
                    row.getResultJson(), row.getErrorMessage(), "DEAD".equals(row.getStatus()));
            afterCommit(() -> dispatcher.reviewed(result));
        } else if ("DRAFT".equals(row.getPhase()) && MediaTaskType.TRANSCODE.name().equals(row.getTaskType())
                && Set.of("SUCCESS", "DEAD").contains(row.getStatus())) {
            afterCommit(() -> {
                Long duration = "SUCCESS".equals(row.getStatus()) ? StructuredJson.object(row.getResultJson()).get("durationMs").getAsLong() : null;
                dispatcher.draftFinished(row.getOwnerId(), "SUCCESS".equals(row.getStatus()), duration, row.getErrorMessage());
                resultReceived(row.getId(), row.getAttempt());
            });
        }
    }
    public boolean retry(Long id) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var pipeline = lock(id);
            if (pipeline == null || "COMPLETED".equals(pipeline.getStatus())) return false;
            if ("PROCESSING".equals(pipeline.getStatus())) return true;
            var nodes = listPipeline(id, "CONTENT");
            for (var row : nodes) if (Set.of("DEAD", "BLOCKED").contains(row.getStatus())) {
                row.setStatus("PENDING").setRetryCount(0).setErrorMessage(null).setCompletionAck(false); save(row);
            }
            pipeline.setStatus("PROCESSING"); advance(pipeline, nodes); return true;
        }));
    }
    public void heartbeat(Long taskId, int attempt) {
        if (tasks.update(null, new LambdaUpdateWrapper<MediaTask>().eq(MediaTask::getId, taskId)
                .eq(MediaTask::getStatus, "RUNNING").eq(MediaTask::getAttempt, attempt)
                .set(MediaTask::getUpdateTime, LocalDateTime.now(ZoneOffset.UTC))) == 0)
            throw new IllegalStateException("Task lease lost");
    }
    public void received(Long pipelineId) {
        pipelines.update(null, new LambdaUpdateWrapper<Pipeline>().eq(Pipeline::getId, pipelineId).eq(Pipeline::getStatus, "COMPLETED")
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
        return pipelines.selectList(new LambdaQueryWrapper<Pipeline>().eq(Pipeline::getStatus, "WAITING_ARTIFACTS")
                .orderByAsc(Pipeline::getId).last("LIMIT " + limit + " OFFSET " + offset)).stream().map(this::view).toList();
    }
    public void recover() {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        long cursor = 0;
        while (true) {
            var page = tasks.selectList(new LambdaQueryWrapper<MediaTask>().gt(MediaTask::getId, cursor)
                    .and(q -> q.eq(MediaTask::getStatus, "READY").le(MediaTask::getDispatchAfter, now)
                            .or().eq(MediaTask::getStatus, "RETRY_WAIT").le(MediaTask::getNextRetryAt, now)
                            .or().eq(MediaTask::getStatus, "RUNNING").lt(MediaTask::getUpdateTime, now.minusSeconds(properties.getLeaseSeconds()))
                            .or().eq(MediaTask::getPhase, "DRAFT").in(MediaTask::getStatus, "SUCCESS", "DEAD").eq(MediaTask::getCompletionAck, false)
                            .or().eq(MediaTask::getPhase, "REVIEW").in(MediaTask::getStatus, "SUCCESS", "DEAD").eq(MediaTask::getCompletionAck, false))
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
        for (var row : rows) {
            if ("RUNNING".equals(row.getStatus()) && row.getUpdateTime().isBefore(now.minusSeconds(properties.getLeaseSeconds()))) {
                finish(row, null, "Worker lease expired"); graphChanged = true;
                if (!"CONTENT".equals(row.getPhase())) notifyResult(row);
            } else if ("RETRY_WAIT".equals(row.getStatus()) && !row.getNextRetryAt().isAfter(now)) {
                metrics.timer("media.node.retry.wait", "type", row.getTaskType()).record(Math.max(0, Duration.between(row.getUpdateTime(), now).toMillis()), TimeUnit.MILLISECONDS);
                ready(row, dependencies(row), input);
            } else if ("READY".equals(row.getStatus()) && !row.getDispatchAfter().isAfter(now)) {
                row.setDispatchAfter(now.plusSeconds(30)); save(row);
                var execution = message(row, input); afterCommit(() -> dispatcher.dispatch(execution));
            } else if (!Boolean.TRUE.equals(row.getCompletionAck()) && Set.of("SUCCESS", "DEAD").contains(row.getStatus())) notifyResult(row);
        }
        if (graphChanged && "CONTENT".equals(first.getPhase())) advance(pipeline, rows);
    }
    public void recoverCompletions() {
        long cursor = 0;
        var now = LocalDateTime.now(ZoneOffset.UTC);
        while (true) {
            var page = pipelines.selectList(new LambdaQueryWrapper<Pipeline>().gt(Pipeline::getId, cursor)
                    .eq(Pipeline::getStatus, "COMPLETED").eq(Pipeline::getReceived, false).le(Pipeline::getNotifyAfter, now)
                    .orderByAsc(Pipeline::getId).last("LIMIT 100"));
            if (page.isEmpty()) break;
            cursor = page.get(page.size()-1).getId();
            for (var p : page) try {
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

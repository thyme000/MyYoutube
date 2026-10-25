package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryItemState;
import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryBatchStatus;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.*;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaRetryBatch;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.media.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaRetryService {
    private final MediaTaskMapper rows;
    private final MediaTaskService tasks;
    private final RetryBatchStore store;
    private final RetryOwnerChecks owners;
    private final TransactionTemplate transaction;
    private final RetryBatchOccupation occupation;
    private final PipelineProperties properties;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.diaojiahong.myyoutube.transcode.cover.CoverProperties cover;

    public CursorPage<Task, Long> query(Query request) {
        if (request==null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "Missing task query");
        validate(request.taskType(),Arrays.stream(MediaTaskType.values()).map(Enum::name).toList());
        validate(request.phase(),List.of("DRAFT","CONTENT","REVIEW"));
        if (!"ALL".equals(request.status()))
            validate(request.status(),Arrays.stream(MediaTaskStatus.values()).map(Enum::name).toList());
        validate(request.dependencyService(),List.of("ASR","OCR","LLM"));
        int limit=request.size()==null ? 20 : request.size();
        if (limit<1 || limit>100 || request.cursor()!=null && request.cursor()<0) throw new BizException(ErrorCode.INVALID_ARGUMENT, "非法查询分页");
        var q=new LambdaQueryWrapper<MediaTask>().gt(request.cursor()!=null,MediaTask::getId,request.cursor())
                .eq(request.taskType()!=null,MediaTask::getTaskType,request.taskType())
                .eq(request.phase()!=null,MediaTask::getPhase,request.phase())
                .eq(request.ownerId()!=null,MediaTask::getOwnerId,request.ownerId())
                .eq(request.pipelineId()!=null,MediaTask::getPipelineId,request.pipelineId());
        if (request.status()==null) q.in(MediaTask::getStatus,MediaTaskStatus.DEAD,MediaTaskStatus.BLOCKED,MediaTaskStatus.RETRY_WAIT);
        else if (!"ALL".equals(request.status())) q.eq(MediaTask::getStatus,MediaTaskStatus.valueOf(request.status()));
        var page=rows.selectList(q.orderByAsc(MediaTask::getId).last("LIMIT "+limit));
        var results=new ArrayList<Task>();
        for (var row : page) {
            var resources=resources(row,new HashSet<>());
            if (request.dependencyService()!=null && !resources.contains(request.dependencyService())) continue;
            String reason=Set.of(MediaTaskStatus.DEAD,MediaTaskStatus.BLOCKED).contains(row.getStatus()) ? null
                    : MediaTaskStatus.RETRY_WAIT.equals(row.getStatus()) ? "AUTOMATIC_RETRY_PENDING" : "TASK_NOT_FAILED";
            if (reason==null) {
                try { reason=owners.reason(row); }
                catch (RuntimeException error) { reason="OWNER_CHECK_UNAVAILABLE"; }
            }
            boolean eligible=reason==null;
            if (request.retryable()!=null && request.retryable()!=eligible) continue;
            results.add(new Task(row.getId(),row.getOwnerId(),row.getPipelineId(),row.getPhase(),row.getTaskType(),row.getStatus(),
                    row.getAttempt(),row.getRetryCount(),row.getFailureCode(),String.join(",",resources),row.getErrorMessage(),eligible,reason,row.getUpdateTime(),row.getNextRetryAt()));
        }
        return new CursorPage<>(results,page.size()<limit ? null : page.get(page.size()-1).getId());
    }
    private SortedSet<String> resources(MediaTask row, Set<Long> visited) {
        var result=new TreeSet<String>();
        if (!visited.add(row.getId())) return result;
        if (row.getDependencyService()!=null) result.add(row.getDependencyService());
        if (result.isEmpty() && !MediaTaskStatus.BLOCKED.equals(row.getStatus())) {
            switch (row.getTaskType()) {
                case "SUMMARY", "TAG", "REVIEW" -> result.add("LLM");
                case "OCR" -> result.add("OCR");
                case "ASR" -> {
                    var media=tasks.retryInput(row).media();
                    if (!(media.preparation()!=null && !media.preparation().hasAudio() || media.dynamic()!=null && media.dynamic().audio()==null)) result.add("ASR");
                }
                case "COVER" -> {
                    if (cover!=null && cover.isVisionEnabled() && tasks.retryInput(row).media().video()!=null) result.add("LLM");
                }
                default -> { }
            }
        }
        if (MediaTaskStatus.BLOCKED.equals(row.getStatus()) && row.getPipelineId()!=null) {
            var nodes=tasks.listPipeline(row.getPipelineId(),row.getPhase());
            for (var node:nodes) if (MediaTaskType.fromCode(row.getTaskType()).getDependencies().contains(node.getTaskType()))
                result.addAll(resources(node,visited));
        }
        return result;
    }
    private void validate(String value,List<String> allowed) {
        if (value!=null && !allowed.contains(value)) throw new BizException(ErrorCode.INVALID_ARGUMENT, "未知任务筛选值: "+value);
    }
    public Admission create(Request request, Long actorId) {
        if (request == null || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_.:-]{1,128}")
                || actorId == null || actorId <= 0 || request.taskIds() == null || request.taskIds().isEmpty()
                || request.taskIds().size() > properties.getRetryBatchSize()
                || request.taskIds().stream().anyMatch(id -> id == null || id <= 0))
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "补跑请求需提供 requestId 和 1~" + properties.getRetryBatchSize() + " 个任务 ID");
        var ids = new TreeSet<>(request.taskIds()).stream().toList();
        var cached = occupation.current();
        if (cached != null) return cachedAdmission(request, actorId, ids, cached);
        var historical = store.findRequest(request.requestId());
        if (historical != null) return replay(historical, actorId, ids);

        var candidates = rows.selectBatchIds(ids).stream().collect(java.util.stream.Collectors.toMap(MediaTask::getId, row -> row));
        var selected = new TreeMap<Long, Integer>();
        var skipped = new HashMap<Long, String>();
        for (var id : ids) {
            var row = candidates.get(id);
            String reason = row == null ? "TASK_MISSING" : !Set.of(MediaTaskStatus.DEAD, MediaTaskStatus.BLOCKED).contains(row.getStatus())
                    ? "TASK_STATE_CHANGED" : owners.reason(row);
            if (reason == null) selected.put(id, row.getAttempt()); else skipped.put(id, reason);
        }
        var mutex = occupation.admissionLock();
        mutex.lock();
        try {
            cached = occupation.current();
            if (cached != null) return cachedAdmission(request, actorId, ids, cached);
            var previous = store.findRequest(request.requestId());
            if (previous != null) return replay(previous, actorId, ids);
            var active = store.active();
            if (active != null) {
                var view = store.batch(active.getId());
                if (MediaRetryBatchStatus.RUNNING.equals(view.status())) {
                    occupation.publish(active, store.selection(active).stream().map(Item::taskId).toList(), view);
                    view = store.batch(active.getId());
                    if (MediaRetryBatchStatus.RUNNING.equals(view.status())) return busy(view);
                }
            }
            return transaction.execute(tx -> {
                var items = ids.stream().map(id -> new Item(id, skipped.containsKey(id) ? MediaRetryItemState.SKIPPED : MediaRetryItemState.PENDING,
                        candidates.containsKey(id) ? candidates.get(id).getAttempt() : -1, null, skipped.get(id))).toList();
                var row = new MediaRetryBatch().setRequestId(request.requestId()).setActorId(actorId).setStatus(MediaRetryBatchStatus.QUEUED)
                        .setActive(1).setEventSequence(0L).setSelectionJson(JsonUtil.toJson(items))
                        .setMaxConcurrency(properties.getRetryConcurrency()).setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
                store.insert(row);
                store.afterCommit(() -> {
                    try {
                        occupation.publish(row, ids, store.snapshot(row).batch());
                        store.batch(row.getId());
                    } catch (RuntimeException error) {
                        log.warn("Retry batch accepted; occupation will be repaired on access batchId={} reason={}", row.getId(), error.toString());
                    }
                });
                skipped.putAll(tasks.resumeSelected(row.getId(), selected));
                items = items.stream().map(item -> skipped.containsKey(item.taskId())
                        ? new Item(item.taskId(), MediaRetryItemState.SKIPPED, item.initialAttempt(), null, skipped.get(item.taskId())) : item).toList();
                store.start(row, items);
                store.afterCommit(() -> log.info("Retry batch accepted batchId={} actorId={} selected={}", row.getId(), actorId, ids.size()));
                return new Admission(true, "RETRY_BATCH_CREATED", "补跑任务已受理", store.snapshot(row).batch());
            });
        } finally {
            mutex.unlock();
        }
    }
    private Admission replay(MediaRetryBatch row, Long actorId, List<Long> ids) {
        if (!actorId.equals(row.getActorId()) || !ids.equals(store.selection(row).stream().map(Item::taskId).toList()))
            throw new BizException(ErrorCode.STATE_CONFLICT, "requestId 已绑定其他补跑清单或操作者");
        return new Admission(true, "RETRY_BATCH_REPLAY", "返回已登记的批次", store.batch(row.getId()));
    }
    private Admission cachedAdmission(Request request, Long actorId, List<Long> ids, RetryBatchOccupation.Occupation cached) {
        if (!request.requestId().equals(cached.requestId())) return busy(cached.batch());
        if (!actorId.equals(cached.actorId()) || !ids.equals(cached.taskIds()))
            throw new BizException(ErrorCode.STATE_CONFLICT, "requestId 已绑定其他补跑清单或操作者");
        return new Admission(true, "RETRY_BATCH_REPLAY", "返回已登记的批次", cached.batch());
    }
    private Admission busy(Batch batch) {
        return new Admission(false, "RETRY_BATCH_RUNNING", "当前已有批次在运行", batch);
    }
}

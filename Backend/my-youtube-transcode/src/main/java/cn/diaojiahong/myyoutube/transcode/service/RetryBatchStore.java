package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryItemState;
import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryBatchStatus;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.pipeline.PipelineEventPort;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort.*;
import cn.diaojiahong.myyoutube.transcode.domain.po.*;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryBatchStore {
    private final MediaRetryBatchMapper batches;
    private final MediaTaskMapper tasks;
    private final RetryBatchOccupation occupation;
    private final TransactionTemplate transaction;

    @Autowired(required = false)
    private PipelineEventPort events;
    @Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry metrics;

    public record Snapshot(Batch batch, List<Item> items, List<Affected> tasks) {}

    public MediaRetryBatch findRequest(String requestId) {
        return batches.selectOne(new LambdaQueryWrapper<MediaRetryBatch>().eq(MediaRetryBatch::getRequestId, requestId));
    }
    public MediaRetryBatch active() {
        return batches.selectOne(new LambdaQueryWrapper<MediaRetryBatch>().eq(MediaRetryBatch::getActive, 1));
    }
    public MediaRetryBatch lock(Long id) {
        return batches.selectOne(new LambdaQueryWrapper<MediaRetryBatch>().eq(MediaRetryBatch::getId, id).last("FOR UPDATE"));
    }
    public List<Item> selection(MediaRetryBatch row) {
        return Arrays.asList(JsonUtil.fromJson(row.getSelectionJson(), Item[].class));
    }
    public void insert(MediaRetryBatch row) {
        batches.insert(row);
        event(row, null, MediaRetryBatchStatus.QUEUED, "RETRY_BATCH_CREATED");
    }
    public void start(MediaRetryBatch row, List<Item> selection) {
        row.setStatus(MediaRetryBatchStatus.RUNNING).setSelectionJson(JsonUtil.toJson(selection));
        event(row, MediaRetryBatchStatus.QUEUED, MediaRetryBatchStatus.RUNNING, "RETRY_BATCH_STARTED");
        batches.updateById(row);
    }
    public MediaRetryBatchStatus status(Long id) {
        var row = batches.selectById(id);
        return row == null ? null : row.getStatus();
    }
    public Batch cachedCurrent() {
        var current = occupation.current();
        return current == null ? null : current.batch();
    }
    public CursorPage<Batch, Long> history(Long cursor, Integer size) {
        int limit = size == null ? 20 : size;
        if (limit < 1 || limit > 100 || cursor != null && cursor <= 0)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "非法历史分页");
        var page = batches.selectList(new LambdaQueryWrapper<MediaRetryBatch>()
                .lt(cursor != null, MediaRetryBatch::getId, cursor).orderByDesc(MediaRetryBatch::getId).last("LIMIT " + limit));
        var views = page.stream().map(row -> row.getResultJson() == null ? batch(row.getId())
                : JsonUtil.fromJson(row.getResultJson(), Snapshot.class).batch()).toList();
        return new CursorPage<>(views, page.size() < limit ? null : page.get(page.size() - 1).getId());
    }
    public Batch current() {
        var cached = cachedCurrent();
        if (cached != null) return cached;
        var row = active();
        if (row == null) return null;
        var view = batch(row.getId());
        return MediaRetryBatchStatus.RUNNING.equals(view.status()) ? view : null;
    }
    public Batch batch(Long id) {
        var snapshot = reconcile(id);
        return snapshot == null ? null : snapshot.batch();
    }
    public List<Item> items(Long id) {
        var snapshot = reconcile(id);
        return snapshot == null ? List.of() : snapshot.items();
    }
    public List<Affected> affected(Long id) {
        var snapshot = reconcile(id);
        return snapshot == null ? List.of() : snapshot.tasks();
    }
    public Snapshot reconcile(Long id) {
        // Completion callbacks still have the committed transaction's resources bound.
        var tx = new TransactionTemplate(transaction.getTransactionManager());
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> {
            var row = lock(id);
            if (row == null) return null;
            if (row.getResultJson() != null) {
                var result = JsonUtil.fromJson(row.getResultJson(), Snapshot.class);
                afterCommit(() -> updateOccupation(result.batch()));
                return result;
            }
            var result = snapshot(row);
            if (result.batch().unfinishedTasks() == 0) {
                MediaRetryBatchStatus state = result.tasks().stream().anyMatch(t -> Set.of(MediaTaskStatus.DEAD, MediaTaskStatus.BLOCKED).contains(t.status())
                        && (t.reason() == null || !t.reason().startsWith("RETRY_SKIPPED_")))
                        ? MediaRetryBatchStatus.COMPLETED_WITH_FAILURES : MediaRetryBatchStatus.COMPLETED;
                row.setStatus(state).setActive(null).setCompletedAt(now());
                result = snapshot(row);
                row.setResultJson(JsonUtil.toJson(result));
                event(row, MediaRetryBatchStatus.RUNNING, state, "RETRY_BATCH_COMPLETED");
                batches.updateById(row);
                afterCommit(() -> {
                    if (metrics != null) metrics.counter("media.retry.batch.completed", "state", state.getCode()).increment();
                    log.info("Retry batch completed batchId={} state={}", id, state);
                });
            }
            var view = result.batch();
            afterCommit(() -> updateOccupation(view));
            return result;
        });
    }
    public void taskChanged(Long id) {
        if (id == null) return;
        afterCommit(() -> {
            try { reconcile(id); }
            catch (RuntimeException error) { log.warn("Retry history will reconcile on access batchId={} reason={}", id, error.toString()); }
        });
    }
    private void updateOccupation(Batch view) {
        try { occupation.update(view); }
        catch (RuntimeException error) { log.warn("Retry occupation will expire batchId={} reason={}", view.batchId(), error.toString()); }
    }
    public Snapshot snapshot(MediaRetryBatch row) {
        var affected = tasks.selectList(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getRetryBatchId, row.getId())
                .orderByAsc(MediaTask::getId));
        var byId = affected.stream().collect(Collectors.toMap(MediaTask::getId, Function.identity()));
        var items = selection(row).stream().map(item -> {
            if (MediaRetryItemState.SKIPPED.equals(item.state())) return item;
            var task = byId.get(item.taskId());
            if (task == null) return new Item(item.taskId(), MediaRetryItemState.SKIPPED, item.initialAttempt(), null, "TASK_MISSING");
            MediaRetryItemState state = switch (task.getStatus()) {
                case SUCCESS -> MediaRetryItemState.SUCCESS;
                case DEAD, BLOCKED -> task.getFailureCode() != null && task.getFailureCode().startsWith("RETRY_SKIPPED_") ? MediaRetryItemState.SKIPPED : MediaRetryItemState.FAILED;
                case PENDING, READY -> MediaRetryItemState.PENDING;
                case RUNNING, RETRY_WAIT -> MediaRetryItemState.RUNNING;
            };
            return new Item(item.taskId(), state, item.initialAttempt(),
                    task.getAttempt() > item.initialAttempt() ? task.getAttempt() : null, task.getFailureCode());
        }).toList();
        var counts = items.stream().collect(Collectors.groupingBy(Item::state, Collectors.counting()));
        int running = (int) affected.stream().filter(t -> MediaTaskStatus.RUNNING.equals(t.getStatus())).count();
        int unfinished = (int) affected.stream().filter(t -> Set.of(MediaTaskStatus.PENDING, MediaTaskStatus.READY, MediaTaskStatus.RUNNING, MediaTaskStatus.RETRY_WAIT).contains(t.getStatus())).count();
        String wait = null;
        if (MediaRetryBatchStatus.RUNNING.equals(row.getStatus()) && unfinished > 0) {
            if (running >= row.getMaxConcurrency()) wait = "RETRY_CONCURRENCY_LIMIT";
            else if (normalReady()) wait = "NORMAL_TASKS_FIRST";
        }
        var view = new Batch(row.getId(), row.getStatus(), items.size(), count(counts, MediaRetryItemState.PENDING), count(counts, MediaRetryItemState.RUNNING),
                count(counts, MediaRetryItemState.SUCCESS), count(counts, MediaRetryItemState.FAILED), count(counts, MediaRetryItemState.SKIPPED), affected.size(), unfinished,
                wait, row.getCreatedAt(), row.getCompletedAt());
        return new Snapshot(view, items, affected.stream().map(t -> new Affected(t.getId(), t.getStatus(), t.getAttempt(), t.getFailureCode())).toList());
    }
    public boolean permit(Long id) {
        var row = batches.selectById(id);
        return row != null && MediaRetryBatchStatus.RUNNING.equals(row.getStatus())
                && tasks.selectCount(new LambdaQueryWrapper<MediaTask>().eq(MediaTask::getRetryBatchId, id)
                        .eq(MediaTask::getStatus, MediaTaskStatus.RUNNING)) < row.getMaxConcurrency()
                && !normalReady();
    }
    private boolean normalReady() {
        return tasks.exists(new LambdaQueryWrapper<MediaTask>().isNull(MediaTask::getRetryBatchId).eq(MediaTask::getStatus, MediaTaskStatus.READY));
    }
    private static int count(Map<MediaRetryItemState, Long> counts, MediaRetryItemState state) { return counts.getOrDefault(state, 0L).intValue(); }
    private void event(MediaRetryBatch row, MediaRetryBatchStatus from, MediaRetryBatchStatus to, String code) {
        if (events == null || !events.enabled()) return;
        long sequence = events.next(row.getEventSequence());
        row.setEventSequence(sequence);
        batches.updateById(row);
        events.record(new PipelineEventPort.Subject("RETRY_BATCH", row.getId(), null, null, null, null, null, null, null), sequence, code, from == null ? null : from.getCode(), to.getCode());
    }
    public void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}

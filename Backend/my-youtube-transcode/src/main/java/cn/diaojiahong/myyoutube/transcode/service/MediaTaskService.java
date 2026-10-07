package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaTaskService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEAD = "DEAD";

    /** 单任务最大重试次数, 超过置 DEAD(编排器集中定义失败策略) */
    public static final int MAX_RETRY = 3;

    private final MediaTaskMapper taskMapper;

    /**
     * 幂等拆任务: uk(draft_id, task_type) 撞唯一键即已拆过, 返回 false
     */
    public boolean createIfAbsent(Long draftId, MediaTaskType type) {
        MediaTask task = new MediaTask()
                .setDraftId(draftId)
                .setTaskType(type.name())
                .setRequiredForReady(type.isRequiredForReady())
                .setStatus(STATUS_PENDING)
                .setAttempt(0)
                .setRetryCount(0);
        try {
            taskMapper.insert(task);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public MediaTask getById(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    public List<MediaTask> listByDraft(Long draftId) {
        return taskMapper.selectList(new LambdaQueryWrapper<MediaTask>()
                .eq(MediaTask::getDraftId, draftId));
    }

    /** 从未派发过的任务(入口消费者/重复事件兜底派发用) */
    public List<MediaTask> listUndispatched(Long draftId) {
        return taskMapper.selectList(new LambdaQueryWrapper<MediaTask>()
                .eq(MediaTask::getDraftId, draftId)
                .eq(MediaTask::getStatus, STATUS_PENDING)
                .eq(MediaTask::getAttempt, 0));
    }

    public List<MediaTask> listByStatus(String... statuses) {
        return taskMapper.selectList(new LambdaQueryWrapper<MediaTask>()
                .in(MediaTask::getStatus, List.of(statuses)));
    }

    /**
     * 认领: PENDING -> RUNNING, attempt = retry_count + 1。
     * 重复投递的消息只有一个消费者能 CAS 成功, 输家短路。
     */
    public Integer claim(Long taskId) {
        MediaTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }
        int attempt = task.getRetryCount() + 1;
        boolean claimed = taskMapper.update(null, new LambdaUpdateWrapper<MediaTask>()
                .eq(MediaTask::getId, taskId)
                .eq(MediaTask::getStatus, STATUS_PENDING)
                .set(MediaTask::getStatus, STATUS_RUNNING)
                .set(MediaTask::getAttempt, attempt)) > 0;
        return claimed ? attempt : null;
    }

    /** 完成提交: 带 attempt 的 CAS, 重复回报/僵尸执行不匹配即丢弃 */
    public boolean complete(MediaTask task, String resultJson) {
        return taskMapper.update(null, new LambdaUpdateWrapper<MediaTask>()
                .eq(MediaTask::getId, task.getId())
                .eq(MediaTask::getStatus, STATUS_RUNNING)
                .eq(MediaTask::getAttempt, task.getAttempt())
                .set(MediaTask::getStatus, STATUS_SUCCESS)
                .set(MediaTask::getResultJson, resultJson)
                .set(MediaTask::getErrorMessage, null)) > 0;
    }

    /** 失败提交: 同样带 attempt 的 CAS */
    public boolean fail(MediaTask task, String errorMessage) {
        String err = errorMessage != null && errorMessage.length() > 500
                ? errorMessage.substring(0, 500) : errorMessage;
        return taskMapper.update(null, new LambdaUpdateWrapper<MediaTask>()
                .eq(MediaTask::getId, task.getId())
                .eq(MediaTask::getStatus, STATUS_RUNNING)
                .eq(MediaTask::getAttempt, task.getAttempt())
                .set(MediaTask::getStatus, STATUS_FAILED)
                .set(MediaTask::getErrorMessage, err)) > 0;
    }

    /** 重试入队: FAILED -> PENDING, 未超上限返回新 retry_count, 超限置 DEAD 返回 -1 */
    public int requeueForRetry(Long taskId) {
        MediaTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return -1;
        }
        if (task.getRetryCount() >= MAX_RETRY) {
            taskMapper.update(null, new LambdaUpdateWrapper<MediaTask>()
                    .eq(MediaTask::getId, taskId)
                    .eq(MediaTask::getStatus, STATUS_FAILED)
                    .set(MediaTask::getStatus, STATUS_DEAD));
            return -1;
        }
        int next = task.getRetryCount() + 1;
        boolean ok = taskMapper.update(null, new LambdaUpdateWrapper<MediaTask>()
                .eq(MediaTask::getId, taskId)
                .eq(MediaTask::getStatus, STATUS_FAILED)
                .set(MediaTask::getStatus, STATUS_PENDING)
                .set(MediaTask::getRetryCount, next)) > 0;
        return ok ? next : -1;
    }

    /** READY 判定: 关键路径(required_for_ready=1)是否全部成功 */
    public boolean allRequiredSucceeded(Long draftId) {
        return taskMapper.selectCount(new LambdaQueryWrapper<MediaTask>()
                .eq(MediaTask::getDraftId, draftId)
                .eq(MediaTask::getRequiredForReady, true)
                .ne(MediaTask::getStatus, STATUS_SUCCESS)) == 0;
    }

    /** 关键路径是否存在终局失败(DEAD/FAILED 且无可重试余地) */
    public boolean anyRequiredDead(Long draftId) {
        return taskMapper.selectCount(new LambdaQueryWrapper<MediaTask>()
                .eq(MediaTask::getDraftId, draftId)
                .eq(MediaTask::getRequiredForReady, true)
                .in(MediaTask::getStatus, STATUS_DEAD)) > 0;
    }

    /**
     * 对账: RUNNING 滞留(回报丢失/worker 崩溃)重置回 PENDING, 等同一次重试入队。
     * 超过重试上限置 DEAD 返回 -1
     */
    public int requeueStuck(Long taskId, java.time.LocalDateTime staleBefore) {
        MediaTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return -1;
        }
        if (task.getRetryCount() >= MAX_RETRY) {
            taskMapper.update(null, new LambdaUpdateWrapper<MediaTask>()
                    .eq(MediaTask::getId, taskId)
                    .eq(MediaTask::getStatus, STATUS_RUNNING)
                    .set(MediaTask::getStatus, STATUS_DEAD));
            return -1;
        }
        int next = task.getRetryCount() + 1;
        boolean ok = taskMapper.update(null, new LambdaUpdateWrapper<MediaTask>()
                .eq(MediaTask::getId, taskId)
                .eq(MediaTask::getStatus, STATUS_RUNNING)
                .lt(MediaTask::getUpdateTime, staleBefore)
                .set(MediaTask::getStatus, STATUS_PENDING)
                .set(MediaTask::getRetryCount, next)) > 0;
        return ok ? next : -1;
    }

    /** 所有终局(非 PENDING/RUNNING)之外仍有活头的草稿id集合 */
    public List<Long> listDraftsWithOpenTasks() {
        return taskMapper.selectList(new LambdaQueryWrapper<MediaTask>()
                        .in(MediaTask::getStatus, STATUS_PENDING, STATUS_RUNNING))
                .stream().map(MediaTask::getDraftId).distinct().toList();
    }

    /** 近期有任务动静的草稿id(对账草稿状态修复用, 限定窗口防止扫描量无界) */
    public List<Long> listDraftsAllTerminal() {
        return taskMapper.selectList(new LambdaQueryWrapper<MediaTask>()
                        .select(MediaTask::getDraftId)
                        .ge(MediaTask::getUpdateTime, LocalDateTime.now().minusDays(2)))
                .stream().map(MediaTask::getDraftId).distinct()
                .toList();
    }
}

package cn.diaojiahong.myyoutube.transcode.schedule;

import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskDispatcher;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账扫描: 事件链路任何一环吞掉消息时的兜底自愈。
 * 1) PENDING 未派发滞留 -> 补派; 2) RUNNING 超时 -> 重置重派; 3) 任务全终局但草稿未推进 -> 补 READY/FAILED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaTaskReconciler {

    /** RUNNING 判定滞留的阈值: 覆盖最长转码耗时 + 回报重试窗口 */
    private static final int RUNNING_STALE_MINUTES = 25;
    private static final int PENDING_STALE_MINUTES = 10;

    private final MediaTaskService taskService;
    private final MediaTaskDispatcher dispatcher;
    private final MediaDraftPort mediaDraftPort;
    private final RocketMQTemplate rocketMQTemplate;

    @Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now();
        // 1+2: 滞留任务重置/补派
        List<MediaTask> openTasks = taskService.listByStatus(
                MediaTaskService.STATUS_PENDING, MediaTaskService.STATUS_RUNNING);
        for (MediaTask task : openTasks) {
            try {
                reconcileTask(task, now);
            } catch (Exception e) {
                log.warn("对账单任务失败: taskId={}", task.getId(), e);
            }
        }
        // 3: 任务全终局但草稿状态滞留(DONE 消费者崩溃在 complete 与 updateStatus 之间)
        for (Long draftId : taskService.listDraftsAllTerminal()) {
            try {
                repairDraftState(draftId);
            } catch (Exception e) {
                log.warn("对账草稿状态失败: draftId={}", draftId, e);
            }
        }
    }

    private void reconcileTask(MediaTask task, LocalDateTime now) {
        if (MediaTaskService.STATUS_PENDING.equals(task.getStatus())) {
            if (task.getAttempt() == 0 && task.getUpdateTime().isBefore(now.minusMinutes(PENDING_STALE_MINUTES))) {
                // 拆完任务没派出去(入口消费者崩溃残留), 直接补派
                dispatcher.dispatch(task);
            }
            return;
        }
        // RUNNING 超时: 回报丢失, 重置回 PENDING 等同一次重试
        if (task.getUpdateTime().isBefore(now.minusMinutes(RUNNING_STALE_MINUTES))) {
            int requeued = taskService.requeueStuck(task.getId(),
                    now.minusMinutes(RUNNING_STALE_MINUTES));
            if (requeued >= 0) {
                log.warn("对账重置滞留任务: taskId={} type={} retry={}", task.getId(), task.getTaskType(), requeued);
                dispatcher.dispatch(taskService.getById(task.getId()));
            } else if (Boolean.TRUE.equals(task.getRequiredForReady())) {
                mediaDraftPort.updateStatus(task.getDraftId(), MediaDraftStatusEnum.FAILED.getCode(),
                        new MediaDraftPort.StatusUpdate(null, task.getTaskType() + " 对账超时"));
            }
        }
    }

    private void repairDraftState(Long draftId) {
        if (taskService.anyRequiredDead(draftId)) {
            mediaDraftPort.updateStatus(draftId, MediaDraftStatusEnum.FAILED.getCode(),
                    new MediaDraftPort.StatusUpdate(null, "关键处理任务失败"));
            return;
        }
        if (taskService.allRequiredSucceeded(draftId)) {
            mediaDraftPort.updateStatus(draftId, MediaDraftStatusEnum.READY.getCode(),
                    new MediaDraftPort.StatusUpdate(null, null));
        }
    }
}

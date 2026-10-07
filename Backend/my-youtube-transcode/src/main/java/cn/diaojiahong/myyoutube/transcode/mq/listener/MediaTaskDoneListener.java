package cn.diaojiahong.myyoutube.transcode.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskDoneMessage;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskDispatcher;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 编排器: 收任务完成回报, CAS 收敛状态并推进 DAG。
 * 成功 -> 推进下游 + 关键路径全成则草稿 READY; 失败 -> 重试入队, 超限 DEAD, 关键任务 DEAD 则草稿 FAILED
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_MEDIA_TASK_DONE,
        consumerGroup = "transcode_media_task_done_group",
        maxReconsumeTimes = 3)
public class MediaTaskDoneListener implements RocketMQListener<MediaTaskDoneMessage> {

    private final MediaTaskService taskService;
    private final MediaTaskDispatcher dispatcher;
    private final MediaDraftPort mediaDraftPort;

    @Override
    public void onMessage(MediaTaskDoneMessage msg) {
        if (msg == null || msg.getTaskId() == null) {
            log.warn("收到无效任务回报: {}", msg);
            return;
        }
        MediaTask row = taskService.getById(msg.getTaskId());
        if (row == null) {
            log.warn("任务回报无对应行, 跳过: taskId={}", msg.getTaskId());
            return;
        }
        Long draftId = row.getDraftId();
        if (Boolean.TRUE.equals(msg.getSuccess())) {
            onTaskSuccess(row, msg, draftId);
        } else {
            onTaskFailure(row, msg, draftId);
        }
    }

    private void onTaskSuccess(MediaTask row, MediaTaskDoneMessage msg, Long draftId) {
        // CAS 带 attempt: 重复回报/僵尸执行不匹配即丢弃
        boolean first = taskService.complete(
                new MediaTask().setId(row.getId()).setAttempt(msg.getAttempt()), msg.getResultJson());
        if (!first) {
            log.info("重复任务回报, 短路: taskId={} attempt={}", row.getId(), msg.getAttempt());
            return;
        }
        // 成功可能解锁下游
        dispatcher.dispatchReadyTasks(draftId);
        if (Boolean.TRUE.equals(row.getRequiredForReady()) && taskService.allRequiredSucceeded(draftId)) {
            boolean ready = mediaDraftPort.updateStatus(draftId, MediaDraftStatusEnum.READY.getCode(),
                    new MediaDraftPort.StatusUpdate(readyDuration(draftId), null));
            if (ready) {
                log.info("草稿 READY: draftId={}", draftId);
                // SSE 进度通知在 video 服务状态迁移侧挂接
            }
        }
    }

    private void onTaskFailure(MediaTask row, MediaTaskDoneMessage msg, Long draftId) {
        boolean first = taskService.fail(
                new MediaTask().setId(row.getId()).setAttempt(msg.getAttempt()), msg.getErrorMessage());
        if (!first) {
            log.info("重复失败回报, 短路: taskId={} attempt={}", row.getId(), msg.getAttempt());
            return;
        }
        int requeued = taskService.requeueForRetry(row.getId());
        if (requeued >= 0) {
            MediaTask fresh = taskService.getById(row.getId());
            log.warn("任务重试入队: taskId={} type={} retry={}", row.getId(), row.getTaskType(), requeued);
            dispatcher.dispatch(fresh);
            return;
        }
        // 终局: DEAD。关键任务拖死草稿, 增强任务只降级
        if (Boolean.TRUE.equals(row.getRequiredForReady())) {
            mediaDraftPort.updateStatus(draftId, MediaDraftStatusEnum.FAILED.getCode(),
                    new MediaDraftPort.StatusUpdate(null,
                            MediaTaskType.fromCode(row.getTaskType()) + " 处理失败: " + msg.getErrorMessage()));
            log.warn("草稿 FAILED(关键任务终局失败): draftId={} taskId={}", draftId, row.getId());
        } else {
            log.warn("增强任务终局失败, 草稿降级可用: draftId={} taskId={} type={}",
                    draftId, row.getId(), row.getTaskType());
        }
    }

    private Long readyDuration(Long draftId) {
        return taskService.listByDraft(draftId).stream()
                .filter(t -> MediaTaskType.TRANSCODE.name().equals(t.getTaskType()))
                .findFirst()
                .map(t -> durationFromResult(t.getResultJson()))
                .orElse(null);
    }

    private Long durationFromResult(String resultJson) {
        if (resultJson == null) {
            return null;
        }
        try {
            long ms = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resultJson).path("durationMs").asLong(0);
            return ms > 0 ? ms : null;
        } catch (Exception e) {
            return null;
        }
    }
}

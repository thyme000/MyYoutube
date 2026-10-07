package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务派发: 依赖满足(PENDING 且从未派发)的任务发短消息, DAG 推进的唯一出口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaTaskDispatcher {

    private final MediaTaskService taskService;
    private final MediaDraftPort mediaDraftPort;
    private final RocketMQTemplate rocketMQTemplate;

    /** 把 draft 下所有"依赖已满足且未派发"的任务发出去; 入口拆任务与编排器推进共用 */
    public void dispatchReadyTasks(Long draftId) {
        var rows = taskService.listByDraft(draftId);
        Set<String> succeeded = rows.stream()
                .filter(t -> MediaTaskService.STATUS_SUCCESS.equals(t.getStatus()))
                .map(MediaTask::getTaskType)
                .collect(Collectors.toSet());
        for (MediaTask row : rows) {
            if (!MediaTaskService.STATUS_PENDING.equals(row.getStatus()) || row.getAttempt() != 0) {
                continue;
            }
            Set<String> deps = MediaTaskType.fromCode(row.getTaskType()).getDependencies();
            if (!succeeded.containsAll(deps)) {
                continue;
            }
            dispatch(row);
        }
    }

    public void dispatch(MediaTask task) {
        MediaDraftPort.DraftInfo draft = mediaDraftPort.getDraft(task.getDraftId());
        if (draft == null) {
            log.warn("派发任务时草稿不存在, 丢弃: draftId={} taskType={}", task.getDraftId(), task.getTaskType());
            return;
        }
        rocketMQTemplate.syncSend(MqConstants.TOPIC_MEDIA_TASK, MediaTaskMessage.builder()
                .taskId(task.getId())
                .draftId(task.getDraftId())
                .userId(draft.getUserId())
                .taskType(task.getTaskType())
                .objectKey(draft.getObjectKey())
                .attempt(task.getAttempt())
                .build());
        log.info("任务已派发 draftId={} type={} attempt={}", task.getDraftId(), task.getTaskType(), task.getAttempt());
    }
}

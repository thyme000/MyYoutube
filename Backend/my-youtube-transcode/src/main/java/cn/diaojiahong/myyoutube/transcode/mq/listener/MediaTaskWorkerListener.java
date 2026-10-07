package cn.diaojiahong.myyoutube.transcode.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskDoneMessage;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import cn.diaojiahong.myyoutube.transcode.task.MediaTaskExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Worker: 认领(CAS) -> 按 taskType 路由执行器 -> 回报完成消息。
 * 认领失败(重复投递/他人在执行)直接 ack 短路。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_MEDIA_TASK,
        consumerGroup = "transcode_media_task_group",
        maxReconsumeTimes = 3)
public class MediaTaskWorkerListener implements RocketMQListener<MediaTaskMessage> {

    private final MediaTaskService taskService;
    private final RocketMQTemplate rocketMQTemplate;
    private final Map<String, MediaTaskExecutor> executors;

    public MediaTaskWorkerListener(MediaTaskService taskService,
                                   RocketMQTemplate rocketMQTemplate,
                                   List<MediaTaskExecutor> executorList) {
        this.taskService = taskService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.executors = executorList.stream()
                .collect(Collectors.toUnmodifiableMap(e -> e.type().name(), Function.identity()));
    }

    @Override
    public void onMessage(MediaTaskMessage msg) {
        if (msg == null || msg.getTaskId() == null) {
            log.warn("收到无效任务消息: {}", msg);
            return;
        }
        Integer attempt = taskService.claim(msg.getTaskId());
        if (attempt == null) {
            log.info("任务认领失败(重复投递或他人在执行), 短路: taskId={} type={}", msg.getTaskId(), msg.getTaskType());
            return;
        }
        MediaTaskExecutor executor = executors.get(msg.getTaskType());
        if (executor == null) {
            report(msg, attempt, false, null, "未知任务类型: " + msg.getTaskType());
            return;
        }
        log.info("任务开始执行 draftId={} type={} attempt={}", msg.getDraftId(), msg.getTaskType(), attempt);
        try {
            String resultJson = executor.execute(msg);
            report(msg, attempt, true, resultJson, null);
            log.info("任务执行成功 draftId={} type={} attempt={}", msg.getDraftId(), msg.getTaskType(), attempt);
        } catch (Exception e) {
            log.error("任务执行失败 draftId={} type={} attempt={}", msg.getDraftId(), msg.getTaskType(), attempt, e);
            report(msg, attempt, false, null, e.getMessage());
        }
    }

    private void report(MediaTaskMessage msg, Integer attempt, boolean success, String resultJson, String errorMessage) {
        try {
            rocketMQTemplate.syncSend(MqConstants.TOPIC_MEDIA_TASK_DONE, MessageBuilder
                    .withPayload(MediaTaskDoneMessage.builder()
                            .taskId(msg.getTaskId())
                            .draftId(msg.getDraftId())
                            .taskType(msg.getTaskType())
                            .success(success)
                            .attempt(attempt)
                            .resultJson(resultJson)
                            .errorMessage(errorMessage)
                            .build())
                    .build());
        } catch (Exception e) {
            // 回报丢失 -> 任务滞留 RUNNING, 由对账扫描超时兜底重派
            log.error("任务完成回报发送失败, 等待对账兜底: taskId={} attempt={}", msg.getTaskId(), attempt, e);
        }
    }
}

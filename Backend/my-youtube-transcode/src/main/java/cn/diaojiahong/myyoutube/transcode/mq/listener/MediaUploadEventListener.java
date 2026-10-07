package cn.diaojiahong.myyoutube.transcode.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.message.MediaUploadEventMessage;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskDispatcher;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 入口消费者: 上传事件 -> 反查草稿 -> 拆任务落库(先于 ack) -> 派发依赖满足的任务。
 * 幂等三层: 草稿状态条件迁移 + uk(draft_id, task_type) + CAS 认领
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_MEDIA_UPLOAD_EVENT,
        consumerGroup = "transcode_media_upload_event_group",
        maxReconsumeTimes = 3)
public class MediaUploadEventListener implements RocketMQListener<MediaUploadEventMessage> {

    private final MediaDraftPort mediaDraftPort;
    private final MediaTaskService taskService;
    private final MediaTaskDispatcher dispatcher;

    @Override
    public void onMessage(MediaUploadEventMessage msg) {
        if (msg == null || msg.getObjectKey() == null) {
            log.warn("收到无效上传事件: {}", msg);
            return;
        }
        MediaDraftPort.DraftInfo draft = mediaDraftPort.getByObjectKey(msg.getObjectKey());
        if (draft == null) {
            // 未经 presign 链路的直传垃圾或已清理对象, 不进管线
            log.warn("上传事件无对应草稿, 跳过: objectKey={}", msg.getObjectKey());
            return;
        }
        if (MediaDraftStatusEnum.READY.getCode().equals(draft.getStatus())
                || MediaDraftStatusEnum.FAILED.getCode().equals(draft.getStatus())
                || MediaDraftStatusEnum.EXPIRED.getCode().equals(draft.getStatus())) {
            log.info("草稿已终态, 跳过重复事件: draftId={} status={}", draft.getId(), draft.getStatus());
            return;
        }
        // 终态之前的重复事件都走全量幂等补齐: 状态迁移 + 拆任务 + 补派, 崩溃残留自愈
        mediaDraftPort.updateStatus(draft.getId(), MediaDraftStatusEnum.PROCESSING.getCode(),
                new MediaDraftPort.StatusUpdate(null, null));
        for (MediaTaskType type : MediaTaskType.values()) {
            taskService.createIfAbsent(draft.getId(), type);
        }
        dispatcher.dispatchReadyTasks(draft.getId());
        log.info("上传事件处理完成 draftId={} objectKey={}", draft.getId(), draft.getObjectKey());
    }
}

package cn.diaojiahong.myyoutube.transcode.mq.listener;

import cn.diaojiahong.myyoutube.transcode.api.enums.TranscodeTaskStatus;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.transcode.chain.dynamic.DynamicTranscodeChain;
import cn.diaojiahong.myyoutube.transcode.chain.dynamic.TranscodeContext;
import cn.diaojiahong.myyoutube.transcode.chain.dynamic.TranscodeNotifier;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mq.message.TranscodeTaskMessage;
import cn.diaojiahong.myyoutube.transcode.service.TranscodeTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_DYNAMIC_TRANSCODE,
        consumerGroup = "dynamic_transcode_group",
        consumeThreadNumber = 1,
        maxReconsumeTimes = 2)
public class TranscodeTaskListener implements RocketMQListener<TranscodeTaskMessage> {

    private final TranscodeTaskService taskService;
    private final TranscodeNotifier notifier;
    private final SyncChainStepRegistry chainRegistry;

    @Override
    public void onMessage(TranscodeTaskMessage msg) {
        if (msg == null || msg.getTaskId() == null) {
            return;
        }
        TranscodeTask task = taskService.getById(msg.getTaskId());
        if (task == null) {
            log.warn("转码任务不存在, 消息已丢弃 taskId={}", msg.getTaskId());
            return;
        }
        if (TranscodeTaskStatus.DONE.equals(task.getStatus()) || TranscodeTaskStatus.FAILED.equals(task.getStatus())) {
            log.debug("转码任务已终态, 跳过重复投递 taskId={} status={}", msg.getTaskId(), task.getStatus());
            return;
        }
        var lock = taskService.artifactLock(task.getTaskKey());
        try {
            lock.lockInterruptibly();
            try { process(taskService.getById(task.getId())); }
            finally { lock.unlock(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待成片任务锁被中断", e);
        }
    }

    private void process(TranscodeTask task) {
        if (task == null || TranscodeTaskStatus.DONE.equals(task.getStatus())
                || TranscodeTaskStatus.FAILED.equals(task.getStatus())) return;
        var cached = taskService.validCompleted(task.getTaskKey());
        if (cached != null) {
            var affected = taskService.listInflightByKey(task.getTaskKey());
            taskService.completeByKey(task.getTaskKey(), cached.getResultObjectKey());
            notifier.notifyAffected(affected, "TRANSCODE_DONE");
            log.info("动态成片复用已完成产物 taskId={} taskKey={} objectKey={} affected={}",
                    task.getId(), task.getTaskKey(), cached.getResultObjectKey(), affected.size());
            return;
        }
        // 认领: PENDING -> PROCESSING, 抢不到说明同任务正被处理
        TranscodeTask claim = new TranscodeTask();
        claim.setStatus(TranscodeTaskStatus.PROCESSING);
        boolean claimed = taskService.updateByIdWithStatus(task.getId(), claim, TranscodeTaskStatus.PENDING);
        if (!claimed && !TranscodeTaskStatus.PROCESSING.equals(task.getStatus())) {
            log.debug("动态成片认领失败, 跳过 taskId={} status={}", task.getId(), task.getStatus());
            return;
        }

        // 准备/下载/合成/上传/完成全在链上, 步骤见 transcode.chain.dynamic;
        // 任一步失败统一落失败终态 + SSE, finally 兜底清理临时目录
        TranscodeContext ctx = TranscodeContext.of(task);
        long startedAt = System.currentTimeMillis();
        try {
            chainRegistry.execute(DynamicTranscodeChain.GROUP, ctx);
        } catch (Exception e) {
            List<TranscodeTask> affected = taskService.listInflightByKey(task.getTaskKey());
            log.error("动态成片生成失败 taskId={} taskKey={} affected={} elapsedMs={}",
                    task.getId(), task.getTaskKey(), affected.size(), System.currentTimeMillis() - startedAt, e);
            taskService.failByKey(task.getTaskKey(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            notifier.notifyAffected(affected, "TRANSCODE_FAILED");
        } finally {
            deleteTreeQuietly(ctx.getWorkRoot());
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}

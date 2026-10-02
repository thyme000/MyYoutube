package cn.diaojiahong.myyoutube.transcode.mq.listener;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.common.utils.DownLoadMP4ToTemp;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.DynamicFfmpegComposer;
import cn.diaojiahong.myyoutube.transcode.mq.message.TranscodeTaskMessage;
import cn.diaojiahong.myyoutube.transcode.service.TranscodeTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_DYNAMIC_TRANSCODE,
        consumerGroup = "dynamic_transcode_group",
        consumeThreadNumber = 1,
        maxReconsumeTimes = 2)
public class TranscodeTaskListener implements RocketMQListener<TranscodeTaskMessage> {

    private static final long STORAGE_SIGN_SECONDS = 3600L * 2;

    private final TranscodeTaskService taskService;
    private final S3StorageUtil storageUtil;
    private final ObjectMapper objectMapper;
    private final PushFanoutPublisher pushFanoutPublisher;

    @Override
    public void onMessage(TranscodeTaskMessage msg) {
        if (msg == null || msg.getTaskId() == null) {
            return;
        }
        TranscodeTask task = taskService.getById(msg.getTaskId());
        if (task == null || TranscodeTaskVO.STATUS_DONE.equals(task.getStatus())
                || TranscodeTaskVO.STATUS_FAILED.equals(task.getStatus())) {
            log.info("转码任务不存在或已终态, 跳过 taskId={} status={}",
                    msg.getTaskId(), task == null ? "null" : task.getStatus());
            return;
        }
        // 认领: PENDING -> PROCESSING, 抢不到说明同任务正被处理
        TranscodeTask claim = new TranscodeTask();
        claim.setStatus(TranscodeTaskVO.STATUS_PROCESSING);
        boolean claimed = taskService.updateByIdWithStatus(task.getId(), claim, TranscodeTaskVO.STATUS_PENDING);
        if (!claimed) {
            return;
        }

        Path workRoot = null;
        try {
            TranscodeSubmitDTO params = objectMapper.readValue(task.getRequestJson(), TranscodeSubmitDTO.class);
            workRoot = Files.createTempDirectory("dynamic-" + task.getId() + "-");
            Path workDir = workRoot.resolve("work");
            Files.createDirectories(workDir);

            List<Path> imagePaths = new ArrayList<>();
            for (var item : params.getItems()) {
                imagePaths.add(downloadTo(item.getImageObjectKey(), workDir));
            }
            Path audioPath = StringUtils.hasText(params.getAudioObjectKey())
                    ? downloadTo(params.getAudioObjectKey(), workDir) : null;

            Path output = workDir.resolve("dynamic-out.mp4");
            DynamicFfmpegComposer.compose(params, imagePaths, audioPath, workDir, output, "ffmpeg");

            byte[] mp4 = Files.readAllBytes(output);
            String objectKey = storageUtil.uploadForObjectKey(
                    mp4,
                    "dynamic-" + task.getTaskKey().substring(0, 16) + ".mp4",
                    task.getUserId(),
                    "transcode"
            );

            List<TranscodeTask> affected = taskService.listInflightByKey(task.getTaskKey());
            taskService.completeByKey(task.getTaskKey(), objectKey);
            notifyAffected(affected, "TRANSCODE_DONE");
            log.info("动态成片生成完成 taskId={} objectKey={} size={}", task.getId(), objectKey, mp4.length);
        } catch (Exception e) {
            log.error("动态成片生成失败 taskId={}", task.getId(), e);
            List<TranscodeTask> affected = taskService.listInflightByKey(task.getTaskKey());
            taskService.failByKey(task.getTaskKey(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            notifyAffected(affected, "TRANSCODE_FAILED");
        } finally {
            if (workRoot != null) {
                deleteTreeQuietly(workRoot);
            }
        }
    }

    private Path downloadTo(String objectKey, Path workDir) throws IOException, InterruptedException {
        String url = storageUtil.signGetUrl(objectKey, STORAGE_SIGN_SECONDS);
        return DownLoadMP4ToTemp.downloadToFixedDir(url, workDir, suffixFromObjectKey(objectKey));
    }

    /** 同 task_key 的所有在途行一起落终态, 逐用户发 SSE(载荷带 taskIds 供前端对号) */
    private void notifyAffected(List<TranscodeTask> affected, String signal) {
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> taskIds = new LinkedHashSet<>();
        for (TranscodeTask t : affected) {
            userIds.add(t.getUserId());
            taskIds.add(t.getId());
        }
        for (Long userId : userIds) {
            pushFanoutPublisher.pushSseSignal(userId, signal, Map.of("taskIds", taskIds));
        }
    }

    private static String suffixFromObjectKey(String key) {
        if (!StringUtils.hasText(key)) {
            return ".bin";
        }
        int dot = key.lastIndexOf('.');
        if (dot < 0 || dot >= key.length() - 1) {
            return ".img";
        }
        String ext = key.substring(dot).toLowerCase(Locale.ROOT);
        if (ext.matches("\\.(jpg|jpeg|png|webp|gif|bmp|mp3|m4a|aac|wav|flac|ogg)")) {
            return ext;
        }
        return ".bin";
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

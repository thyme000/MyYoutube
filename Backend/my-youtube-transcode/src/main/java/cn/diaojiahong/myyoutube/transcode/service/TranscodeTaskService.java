package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.transcode.api.enums.TranscodeTaskStatus;
import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mapper.TranscodeTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeTaskService {

    private final TranscodeTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final RedissonClient redisson;
    private final S3StorageUtil storage;

    public RLock artifactLock(String taskKey) {
        return redisson.getLock("content:export:" + taskKey);
    }

    /**
     * 内容哈希做 task_key: 只取影响成片内容的字段, 不含请求者与动态 id, 不同用户下载同一份内容共享缓存。
     */
    public String taskKeyOf(TranscodeSubmitDTO dto) {
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("composition", "image-loop-audio-v1");
            content.put("canvasW", dto.getCanvasW());
            content.put("canvasH", dto.getCanvasH());
            content.put("transition", dto.getTransition());
            content.put("fadeMs", dto.getFadeMs());
            content.put("audioObjectKey", dto.getAudioObjectKey());
            content.put("audioDurationMs", dto.getAudioDurationMs());
            content.put("totalDurationMs", dto.getTotalDurationMs());
            content.put("items", dto.getItems().stream().map(it -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("imageObjectKey", it.getImageObjectKey());
                m.put("startMs", it.getStartMs());
                m.put("durationMs", it.getDurationMs());
                m.put("focusX", it.getFocusX());
                m.put("focusY", it.getFocusY());
                return m;
            }).toList());
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(content));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("计算 taskKey 失败", e);
        }
    }

    /**
     * 幂等提交: 同 key 已有 DONE → 为本请求者镜像一条即时 DONE; 否则独立建 PENDING 行。
     */
    public TranscodeTask submit(TranscodeSubmitDTO dto) throws Exception {
        String taskKey = taskKeyOf(dto);
        String requestJson = objectMapper.writeValueAsString(dto);

        RLock lock = artifactLock(taskKey);
        if (!lock.tryLock()) return insert(dto, taskKey, requestJson, TranscodeTaskStatus.PENDING, null);
        try {
            TranscodeTask done = validCompleted(taskKey);
            return insert(
                    dto,
                    taskKey,
                    requestJson,
                    done == null ? TranscodeTaskStatus.PENDING : TranscodeTaskStatus.DONE,
                    done == null ? null : done.getResultObjectKey());
        } finally {
            lock.unlock();
        }
    }

    public TranscodeTask validCompleted(String taskKey) {
        TranscodeTask done = taskMapper.selectOne(new LambdaQueryWrapper<TranscodeTask>()
                .eq(TranscodeTask::getTaskKey, taskKey)
                .eq(TranscodeTask::getStatus, TranscodeTaskStatus.DONE)
                .last("limit 1"));
        if (done == null || done.getResultObjectKey() == null || done.getResultObjectKey().isBlank()) return null;
        if (storage.objectExists(done.getResultObjectKey())) return done;
        taskMapper.update(null, new LambdaUpdateWrapper<TranscodeTask>().eq(TranscodeTask::getTaskKey, taskKey)
                .eq(TranscodeTask::getStatus, TranscodeTaskStatus.DONE).set(
                        TranscodeTask::getStatus,
                        TranscodeTaskStatus.EXPIRED));
        return null;
    }

    public TranscodeTask getById(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    /** 带状态条件的更新, 返回是否命中(用于任务认领) */
    public boolean updateByIdWithStatus(Long id, TranscodeTask patch, TranscodeTaskStatus expectedStatus) {
        return taskMapper.update(patch, new LambdaUpdateWrapper<TranscodeTask>()
                .eq(TranscodeTask::getId, id)
                .eq(TranscodeTask::getStatus, expectedStatus)) > 0;
    }

    public List<TranscodeTask> listInflightByKey(String taskKey) {
        return taskMapper.selectList(new LambdaQueryWrapper<TranscodeTask>()
                .eq(TranscodeTask::getTaskKey, taskKey)
                .in(TranscodeTask::getStatus, TranscodeTaskStatus.PENDING, TranscodeTaskStatus.PROCESSING));
    }

    public void completeByKey(String taskKey, String resultObjectKey) {
        TranscodeTask patch = new TranscodeTask();
        patch.setStatus(TranscodeTaskStatus.DONE);
        patch.setResultObjectKey(resultObjectKey);
        taskMapper.update(patch, new LambdaQueryWrapper<TranscodeTask>()
                .eq(TranscodeTask::getTaskKey, taskKey)
                .in(TranscodeTask::getStatus, TranscodeTaskStatus.PENDING, TranscodeTaskStatus.PROCESSING));
    }

    public void failByKey(String taskKey, String errorMessage) {
        String err = errorMessage != null
                && errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
        TranscodeTask patch = new TranscodeTask();
        patch.setStatus(TranscodeTaskStatus.FAILED);
        patch.setErrorMessage(err);
        taskMapper.update(patch, new LambdaQueryWrapper<TranscodeTask>()
                .eq(TranscodeTask::getTaskKey, taskKey)
                .in(TranscodeTask::getStatus, TranscodeTaskStatus.PENDING, TranscodeTaskStatus.PROCESSING));
    }

    private TranscodeTask insert(TranscodeSubmitDTO dto, String taskKey, String requestJson,
                                 TranscodeTaskStatus status, String resultObjectKey) {
        TranscodeTask task = new TranscodeTask();
        task.setTaskKey(taskKey);
        task.setContentId(dto.getContentId());
        task.setUserId(dto.getUserId());
        task.setStatus(status);
        task.setRequestJson(requestJson);
        task.setResultObjectKey(resultObjectKey);
        taskMapper.insert(task);
        return task;
    }
}

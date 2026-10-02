package cn.diaojiahong.myyoutube.transcode.service;

import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mapper.TranscodeTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    /**
     * 内容哈希做 task_key: 只取影响成片内容的字段, 不含请求者与动态 id, 不同用户下载同一份内容共享缓存。
     */
    public String taskKeyOf(TranscodeSubmitDTO dto) {
        try {
            Map<String, Object> content = new LinkedHashMap<>();
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

        TranscodeTask done = taskMapper.selectOne(new LambdaQueryWrapper<TranscodeTask>()
                .eq(TranscodeTask::getTaskKey, taskKey)
                .eq(TranscodeTask::getStatus, TranscodeTaskVO.STATUS_DONE)
                .last("limit 1"));
        if (done != null && done.getResultObjectKey() != null && !done.getResultObjectKey().isEmpty()) {
            return insert(dto, taskKey, requestJson, TranscodeTaskVO.STATUS_DONE, done.getResultObjectKey());
        }
        return insert(dto, taskKey, requestJson, TranscodeTaskVO.STATUS_PENDING, null);
    }

    public TranscodeTask getById(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    /** 带状态条件的更新, 返回是否命中(用于任务认领) */
    public boolean updateByIdWithStatus(Long id, TranscodeTask patch, String expectedStatus) {
        return taskMapper.update(patch, new LambdaUpdateWrapper<TranscodeTask>()
                .eq(TranscodeTask::getId, id)
                .eq(TranscodeTask::getStatus, expectedStatus)) > 0;
    }

    public List<TranscodeTask> listInflightByKey(String taskKey) {
        return taskMapper.selectList(new LambdaQueryWrapper<TranscodeTask>()
                .eq(TranscodeTask::getTaskKey, taskKey)
                .in(TranscodeTask::getStatus, TranscodeTaskVO.STATUS_PENDING, TranscodeTaskVO.STATUS_PROCESSING));
    }

    public void completeByKey(String taskKey, String resultObjectKey) {
        TranscodeTask patch = new TranscodeTask();
        patch.setStatus(TranscodeTaskVO.STATUS_DONE);
        patch.setResultObjectKey(resultObjectKey);
        taskMapper.update(patch, new LambdaQueryWrapper<TranscodeTask>()
                .eq(TranscodeTask::getTaskKey, taskKey)
                .in(TranscodeTask::getStatus, TranscodeTaskVO.STATUS_PENDING, TranscodeTaskVO.STATUS_PROCESSING));
    }

    public void failByKey(String taskKey, String errorMessage) {
        String err = errorMessage != null && errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
        TranscodeTask patch = new TranscodeTask();
        patch.setStatus(TranscodeTaskVO.STATUS_FAILED);
        patch.setErrorMessage(err);
        taskMapper.update(patch, new LambdaQueryWrapper<TranscodeTask>()
                .eq(TranscodeTask::getTaskKey, taskKey)
                .in(TranscodeTask::getStatus, TranscodeTaskVO.STATUS_PENDING, TranscodeTaskVO.STATUS_PROCESSING));
    }

    private TranscodeTask insert(TranscodeSubmitDTO dto, String taskKey, String requestJson,
                                 String status, String resultObjectKey) {
        TranscodeTask task = new TranscodeTask();
        task.setTaskKey(taskKey);
        task.setDynamicId(dto.getDynamicId());
        task.setUserId(dto.getUserId());
        task.setStatus(status);
        task.setRequestJson(requestJson);
        task.setResultObjectKey(resultObjectKey);
        taskMapper.insert(task);
        return task;
    }
}

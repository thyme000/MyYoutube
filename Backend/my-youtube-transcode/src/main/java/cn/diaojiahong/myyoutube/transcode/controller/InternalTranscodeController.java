package cn.diaojiahong.myyoutube.transcode.controller;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mq.message.TranscodeTaskMessage;
import cn.diaojiahong.myyoutube.transcode.service.TranscodeTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部接口: 供 video 服务经 Feign 调用。提交按 task_key(内容哈希) 幂等 ——
 * 已有 DONE 直接镜像一条即时完成记录(成片缓存共享); 在途任务各请求者独立建行,
 * 完成时同 key 全部落 DONE 并逐用户 SSE 通知。
 */
@Slf4j
@RestController
@RequestMapping("/internal/transcode")
@RequiredArgsConstructor
public class InternalTranscodeController {

    private final TranscodeTaskService taskService;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;

    /** 内部契约接口, 与其他 internal 控制器一致直接返回 DTO(不走 Result 信封), 异常场景返回 null */
    @PostMapping("/tasks")
    public TranscodeTaskVO submitTask(@RequestBody TranscodeSubmitDTO dto) {
        if (dto == null || dto.getUserId() == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            return null;
        }
        TranscodeTask task;
        try {
            task = taskService.submit(dto);
        } catch (Exception e) {
            log.error("创建转码任务失败 dynamicId={}", dto.getDynamicId(), e);
            return null;
        }
        if (TranscodeTaskVO.STATUS_PENDING.equals(task.getStatus())) {
            rocketMQTemplate.syncSend(MqConstants.TOPIC_DYNAMIC_TRANSCODE, new TranscodeTaskMessage(task.getId()));
        }
        touchLru(task.getTaskKey());
        return toVO(task);
    }

    @GetMapping("/tasks/{taskId}")
    public TranscodeTaskVO getTask(@PathVariable Long taskId) {
        TranscodeTask task = taskService.getById(taskId);
        if (task == null) {
            return null;
        }
        if (TranscodeTaskVO.STATUS_DONE.equals(task.getStatus())) {
            touchLru(task.getTaskKey());
        }
        return toVO(task);
    }

    private void touchLru(String taskKey) {
        if (StringUtils.hasText(taskKey)) {
            redisTemplate.opsForZSet().add(RedisConstants.TRANSCODE_LRU, taskKey, System.currentTimeMillis());
        }
    }

    private TranscodeTaskVO toVO(TranscodeTask task) {
        return TranscodeTaskVO.builder()
                .taskId(task.getId())
                .taskKey(task.getTaskKey())
                .status(task.getStatus())
                .userId(task.getUserId())
                .resultObjectKey(task.getResultObjectKey())
                .errorMessage(task.getErrorMessage())
                .build();
    }
}

package cn.diaojiahong.myyoutube.transcode.api;

import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * transcode 服务对外契约: 动态成片懒转码任务的提交与查询。
 * 提交按 task_key(内容哈希) 幂等, 同 key 在途/已完成任务直接复用。
 */
@FeignClient(name = "my-youtube-transcode-service", contextId = "transcodePort",
        path = "/internal/transcode", url = "${my-youtube.transcode-service.url:}")
public interface TranscodePort {

    @PostMapping("/tasks")
    TranscodeTaskVO submitTask(@RequestBody TranscodeSubmitDTO submitDTO);

    @GetMapping("/tasks/{taskId}")
    TranscodeTaskVO getTask(@PathVariable("taskId") Long taskId);
}

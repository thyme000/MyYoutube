package cn.diaojiahong.myyoutube.transcode.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * transcode 服务对外契约: 草稿媒体任务结果查询, 供 video 服务发布时读取
 */
@FeignClient(name = "my-youtube-transcode-service", contextId = "mediaTaskPort",
        path = "/internal/media", url = "${my-youtube.transcode-service.url:}")
public interface MediaTaskPort {

    @GetMapping("/tasks/{draftId}")
    List<TaskResult> getTaskResults(@PathVariable("draftId") Long draftId);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class TaskResult {
        private String taskType;
        private String status;
        private String resultJson;
    }
}

package cn.diaojiahong.myyoutube.transcode.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@FeignClient(name = "my-youtube-transcode-service", contextId = "mediaTaskPort",
        path = "/internal/media", url = "${my-youtube.transcode-service.url:}")
public interface MediaTaskPort {
    @GetMapping("/tasks/{ownerId}")
    List<TaskResult> getTaskResults(@PathVariable("ownerId") Long ownerId);
    @GetMapping("/pipelines/{pipelineId}")
    PipelineView pipeline(@PathVariable("pipelineId") Long pipelineId);
    @GetMapping("/pipelines/waiting")
    List<PipelineView> waiting(@RequestParam("page") int page, @RequestParam("size") int size);
    @PostMapping("/pipelines/{pipelineId}/retry")
    boolean retry(@PathVariable("pipelineId") Long pipelineId);
    @PostMapping("/pipelines/{pipelineId}/received")
    void received(@PathVariable("pipelineId") Long pipelineId);
    @PostMapping("/tasks/{taskId}/received/{attempt}")
    void resultReceived(@PathVariable("taskId") Long taskId, @PathVariable("attempt") int attempt);
    record TaskResult(String taskType, String status, String resultJson) {}
    record Node(Long taskId, String taskType, String phase, String status, int attempt, int retryCount,
                String resultJson, String errorMessage) {}
    record PipelineView(Long pipelineId, Long contentId, String status, List<Node> tasks) {}
}

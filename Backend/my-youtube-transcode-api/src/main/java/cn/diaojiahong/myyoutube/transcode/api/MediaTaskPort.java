package cn.diaojiahong.myyoutube.transcode.api;

import cn.diaojiahong.myyoutube.common.media.PipelineStatus;
import cn.diaojiahong.myyoutube.common.media.MediaTaskStatus;
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
    @PostMapping("/preparations")
    void prepare(@RequestBody cn.diaojiahong.myyoutube.common.media.PreparationContracts.Request request);
    @GetMapping("/preparations/{preparationId}")
    cn.diaojiahong.myyoutube.common.media.PreparationContracts.View preparation(@PathVariable("preparationId") Long preparationId);
    @PostMapping("/preparations/{preparationId}/retry")
    boolean retryPreparation(@PathVariable("preparationId") Long preparationId);
    record TaskResult(String taskType, String status, String resultJson) {}
    record Node(Long taskId, String taskType, String phase, MediaTaskStatus status, int attempt, int retryCount,
                String resultJson, String errorMessage) {}
    record PipelineView(Long pipelineId, Long contentId, PipelineStatus status, List<Node> tasks) {}
}

package cn.diaojiahong.myyoutube.transcode.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

@FeignClient(name = "my-youtube-transcode-service", contextId = "mediaRetryPort",
        path = "/internal/media-retries", url = "${my-youtube.transcode-service.url:}")
public interface MediaRetryPort {
    record Query(String taskType, String dependencyService, String phase, String status,
                 Long ownerId, Long pipelineId, Long cursor, Integer size, Boolean retryable) {}
    record Task(Long taskId, Long ownerId, Long pipelineId, String phase, String taskType, String status,
                int attempt, int retryCount, String failureCode, String dependencyService, String error,
                boolean retryable, String cannotRetryReason, LocalDateTime updatedAt, LocalDateTime nextRetryAt) {}
    record Page(List<Task> items, Long nextCursor) {}
    record Request(String requestId, List<Long> taskIds) {}
    record Batch(Long batchId, String status, int total, int pending, int running, int succeeded,
                 int failed, int skipped, int affectedTasks, int unfinishedTasks, String waitReason,
                 LocalDateTime createdAt, LocalDateTime completedAt) {}
    record BatchPage(List<Batch> items, Long nextCursor) {}
    record Admission(boolean accepted, String code, String message, Batch batch) {}
    record Affected(Long taskId, String status, int attempt, String reason) {}
    record Item(Long taskId, String state, int initialAttempt, Integer executionAttempt, String reason) {}
    @PostMapping("/query") Page query(@RequestBody Query query);
    @PostMapping("/batches") Admission create(@RequestBody Request request, @RequestParam("actorId") Long actorId);
    @GetMapping("/batches") BatchPage history(@RequestParam(value="cursor", required=false) Long cursor,
            @RequestParam(value="size", defaultValue="20") Integer size);
    @GetMapping("/batches/current") Batch current();
    @GetMapping("/batches/{batchId}") Batch batch(@PathVariable("batchId") Long batchId);
    @GetMapping("/batches/{batchId}/tasks") List<Affected> affected(@PathVariable("batchId") Long batchId);
    @GetMapping("/batches/{batchId}/items") List<Item> items(@PathVariable("batchId") Long batchId);
}

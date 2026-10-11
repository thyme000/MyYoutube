package cn.diaojiahong.myyoutube.video.api;

import cn.diaojiahong.myyoutube.common.media.PipelineContracts.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "my-youtube-video-service", contextId = "contentPipelinePort",
        path = "/internal/content-pipeline", url = "${my-youtube.video-service.url:}")
public interface ContentPipelinePort {
    @PostMapping("/tags")
    String generateTags(@RequestBody TagRequest request);
    @PostMapping("/{pipelineId}/review/{attempt}")
    ReviewSpec reviewSpec(@PathVariable("pipelineId") Long pipelineId, @PathVariable("attempt") int attempt);
}

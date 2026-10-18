package cn.diaojiahong.myyoutube.video.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "my-youtube-video-service", contextId = "mediaRetryOwnerPort",
        path = "/internal/media-retry-owner", url = "${my-youtube.video-service.url:}")
public interface MediaRetryOwnerPort {
    record Owner(String phase, Long ownerId, Long pipelineId, Long preparationId, Long mediaVersion) {}
    record Eligibility(boolean allowed, String reason) {}
    @PostMapping("/check") Eligibility check(@RequestBody Owner owner);
}

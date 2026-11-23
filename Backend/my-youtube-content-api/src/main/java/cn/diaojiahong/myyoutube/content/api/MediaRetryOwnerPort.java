package cn.diaojiahong.myyoutube.content.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "my-youtube-content-service", contextId = "mediaRetryOwnerPort",
        path = "/internal/media-retry-owner", url = "${my-youtube.content-service.url:}")
public interface MediaRetryOwnerPort {
    record Owner(String phase, Long ownerId, Long pipelineId, Long preparationId, Long mediaVersion) {
    }

    record Eligibility(boolean allowed, String reason) {
    }

    @PostMapping("/check")
    Eligibility check(@RequestBody Owner owner);
}

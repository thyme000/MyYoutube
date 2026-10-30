package cn.diaojiahong.myyoutube.video.api;

import cn.diaojiahong.myyoutube.common.mq.message.ContentDocMessage;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "my-youtube-video-service", contextId = "contentInternalPort",
        path = "/internal/content", url = "${my-youtube.video-service.url:}")
public interface ContentInternalPort {
    @GetMapping("/search-document/{contentId}")
    ContentDocMessage searchDocument(@PathVariable("contentId") Long contentId);

    @GetMapping("/forward-info/{contentId}")
    ForwardInfo forwardInfo(@PathVariable("contentId") Long contentId, @RequestParam("viewerId") Long viewerId);

    @PostMapping("/share/{contentId}")
    boolean incrementShareCount(@PathVariable("contentId") Long contentId, @RequestParam("viewerId") Long viewerId);

    @PostMapping("/liked-map")
    java.util.Map<Long, Boolean> likedMap(
            @RequestParam("userId") Long userId,
            @RequestBody java.util.Set<Long> contentIds);

    record ForwardInfo(Long contentId, String type, String title, String coverObjectKey) {
    }
}

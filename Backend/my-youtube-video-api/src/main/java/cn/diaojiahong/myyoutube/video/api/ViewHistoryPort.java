package cn.diaojiahong.myyoutube.video.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "my-youtube-video-service", contextId = "viewHistoryPort",
        path = "/internal/view-history", url = "${my-youtube.video-service.url:}")
public interface ViewHistoryPort {

    int MAX_CANDIDATES = 1000;

    /** 仅返回该用户观看历史与候选作品的交集。 */
    @PostMapping("/viewed-ids")
    List<Long> viewedIds(@RequestBody Candidates query);
    record Candidates(Long userId, List<Long> contentIds) {
    }
}

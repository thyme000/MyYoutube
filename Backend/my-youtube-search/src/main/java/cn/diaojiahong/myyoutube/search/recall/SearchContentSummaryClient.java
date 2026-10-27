package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;

/** 搜索回源使用独立的固定网络超时。 */
@FeignClient(name = "my-youtube-video-service", contextId = "searchContentSummaryClient", path = "/internal/content-summary",
        url = "${my-youtube.video-service.url:}")
public interface SearchContentSummaryClient extends ContentSummaryPort {
    @PostMapping("/list-ordered")
    List<ContentSummary> listByIdsOrdered(@RequestBody SummaryQuery query);
}

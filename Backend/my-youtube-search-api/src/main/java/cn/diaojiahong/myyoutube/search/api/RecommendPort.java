package cn.diaojiahong.myyoutube.search.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * search 服务对外契约: 推荐接口
 */
@FeignClient(name = "my-youtube-search-service", contextId = "recommendPort", path = "/internal/recommend",
        url = "${my-youtube.search-service.url:}")
public interface RecommendPort {

    @PostMapping("/by-user")
    List<Long> recommend(@RequestBody RecommendQuery query);

    @PostMapping("/for-follow")
    List<Long> recommendForFollow(@RequestBody FollowRecommendQuery query);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class RecommendQuery {
        private Long userId;
        /** 内容类型: null/空=混合流, 非空=独立类型流(video/dynamic/note...) */
        private String type;
        private String category;
        private int page;
        private int size;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class FollowRecommendQuery {
        private List<Long> followIds;
        private Long userId;
        private int page;
        private int size;
    }
}

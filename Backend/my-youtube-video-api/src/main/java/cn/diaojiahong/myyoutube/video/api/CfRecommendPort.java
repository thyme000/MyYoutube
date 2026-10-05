package cn.diaojiahong.myyoutube.video.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * video 服务对外契约: 协同过滤在线召回(种子扩散出候选内容)
 */
@FeignClient(name = "my-youtube-video-service", contextId = "cfRecommendPort",
        path = "/internal/recommend-cf", url = "${my-youtube.video-service.url:}")
public interface CfRecommendPort {

    @PostMapping("/candidates")
    CfCandidates candidates(@RequestBody CfCandidatesQuery query);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class CfCandidatesQuery {
        private Long userId;
        /** 内容类型过滤: null/空=混合流全部池, 非空=独立类型流(只扩散该类型的种子) */
        private String type;
        private int limit;
    }

    /**
     * @param algorithm 实际使用的相似度算法(itemcf/swing), 供下游日志观测
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class CfCandidates {
        private String algorithm;
        private List<Long> contentIds;
    }
}

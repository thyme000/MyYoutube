package cn.diaojiahong.myyoutube.search.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * search 服务对外契约: 用户 ES 文档计数调整
 */
@FeignClient(name = "my-youtube-search-service", contextId = "userDocPort",
        path = "/internal/user-doc", url = "${my-youtube.search-service.url:}")
public interface UserDocPort {

    /** 作品数增减(负数减, 减到 0 为止由实现保证) */
    @PostMapping("/video-count/adjust")
    void adjustVideoCount(@RequestParam("userId") Long userId, @RequestParam("delta") Integer delta);

    /** 获赞数增量同步(定时任务用) */
    @PostMapping("/like-count/adjust")
    void adjustLikeCount(@RequestParam("userId") Long userId, @RequestParam("delta") Long delta);

    /** 获赞数批量增量同步(计数落库整轮一次请求) */
    @PostMapping("/like-count/adjust-batch")
    void adjustLikeCountBatch(@RequestBody List<LikeCountSync> syncs);

    public static record LikeCountSync(Long userId, Long delta) {
    }
}

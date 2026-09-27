package cn.diaojiahong.myyoutube.search.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
}

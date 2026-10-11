package cn.diaojiahong.myyoutube.search.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * search 服务对外契约: 内容池 ES 文档操作。
 * 拆部署前由 search 模块的 ContentDocPortImpl 提供本地实现;
 * 拆部署后走 Feign, 过渡期用 my-youtube.search-service.url 指向旧单体。
 */
@FeignClient(name = "my-youtube-search-service", contextId = "contentDocPort", path = "/internal/content-doc",
        url = "${my-youtube.search-service.url:}")
public interface ContentDocPort {

    @PostMapping("/rebuild")
    void rebuildContent(@RequestParam("contentId") Long contentId);

    @PostMapping("/delete")
    void deleteDoc(@RequestParam("contentId") Long contentId);

    /** MySQL 为准批量同步播放量/点赞量(定时任务用), 一次请求整页 */
    @PostMapping("/play-like/sync-batch")
    void syncPlayLikeBatch(@RequestBody List<CountSync> counts);

    public static record CountSync(Long contentId, Long playCount, Long likeCount) {
    }
}

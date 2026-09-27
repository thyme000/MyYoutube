package cn.diaojiahong.myyoutube.search.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * search 服务对外契约: 视频 ES 文档操作。
 * 拆部署前由 search 模块的 VideoDocPortImpl 提供本地实现;
 * 拆部署后走 Feign, 过渡期用 my-youtube.search-service.url 指向旧单体。
 */
@FeignClient(name = "my-youtube-search-service", contextId = "videoDocPort", path = "/internal/video-doc",
        url = "${my-youtube.search-service.url:}")
public interface VideoDocPort {

    @PostMapping("/delete")
    void deleteDoc(@RequestParam("videoId") Long videoId);

    /**
     * 同步视频可见性到 ES 文档
     *
     * @param rebuildSuggest public 且为 true 时按 title/description/tags 重建搜索补全词
     */
    @PostMapping("/visibility")
    void syncVisibility(@RequestParam("videoId") Long videoId,
                        @RequestBody VisibilitySync sync);

    /** 播放量增量同步(定时任务用) */
    @PostMapping("/play-count/adjust")
    void adjustPlayCount(@RequestParam("videoId") Long videoId, @RequestParam("delta") Long delta);

    /** MySQL 为准同步播放量/点赞量(定时任务用) */
    @PostMapping("/play-like/sync")
    void syncPlayLike(@RequestParam("videoId") Long videoId,
                      @RequestParam("playCount") Long playCount,
                      @RequestParam("likeCount") Long likeCount);

    /** 审核状态同步(pass 重建补全词, ban 清空) */
    @PostMapping("/status")
    void syncStatus(@RequestParam("videoId") Long videoId,
                    @RequestBody StatusSync sync);

    public static record StatusSync(String statusCode, String title, String description, String tags,
                                    boolean rebuildSuggest) {
    }
}

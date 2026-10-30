package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import cn.diaojiahong.myyoutube.search.port.RecommendPortImpl;
import cn.diaojiahong.myyoutube.search.port.ContentDocPortImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * search 服务内部接口: 供 video 等服务经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalSearchController {

    private final ContentDocPortImpl contentDocPort;
    private final RecommendPortImpl recommendPort;

    @PostMapping("/content-doc/rebuild")
    public void rebuildContent(@RequestParam("contentId") Long contentId) {
        contentDocPort.rebuildContent(contentId);
    }

    @PostMapping("/content-doc/delete")
    public void deleteDoc(@RequestParam("contentId") Long contentId) {
        contentDocPort.deleteDoc(contentId);
    }

    @PostMapping("/content-doc/play-like/sync-batch")
    public void syncPlayLikeBatch(@RequestBody List<ContentDocPort.CountSync> counts) {
        contentDocPort.syncPlayLikeBatch(counts);
    }

    @PostMapping("/recommend/by-user")
    public List<Long> recommend(@RequestBody RecommendPort.RecommendQuery query) {
        return recommendPort.recommend(query);
    }

    @PostMapping("/recommend/for-follow")
    public List<Long> recommendForFollow(@RequestBody RecommendPort.FollowRecommendQuery query) {
        return recommendPort.recommendForFollow(query);
    }
}

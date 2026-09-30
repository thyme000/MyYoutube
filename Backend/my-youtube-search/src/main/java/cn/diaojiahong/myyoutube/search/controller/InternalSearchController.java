package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.search.api.VideoDocPort;
import cn.diaojiahong.myyoutube.search.api.VisibilitySync;
import cn.diaojiahong.myyoutube.search.port.RecommendPortImpl;
import cn.diaojiahong.myyoutube.search.port.VideoDocPortImpl;
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

    private final VideoDocPortImpl videoDocPort;
    private final RecommendPortImpl recommendPort;

    @PostMapping("/video-doc/delete")
    public void deleteDoc(@RequestParam("videoId") Long videoId) {
        videoDocPort.deleteDoc(videoId);
    }

    @PostMapping("/video-doc/visibility")
    public void syncVisibility(@RequestParam("videoId") Long videoId, @RequestBody VisibilitySync sync) {
        videoDocPort.syncVisibility(videoId, sync);
    }

    @PostMapping("/video-doc/play-count/adjust")
    public void adjustPlayCount(@RequestParam("videoId") Long videoId, @RequestParam("delta") Long delta) {
        videoDocPort.adjustPlayCount(videoId, delta);
    }

    @PostMapping("/video-doc/play-like/sync")
    public void syncPlayLike(@RequestParam("videoId") Long videoId,
                             @RequestParam("playCount") Long playCount,
                             @RequestParam("likeCount") Long likeCount) {
        videoDocPort.syncPlayLike(videoId, playCount, likeCount);
    }

    @PostMapping("/video-doc/status")
    public void syncStatus(@RequestParam("videoId") Long videoId, @RequestBody VideoDocPort.StatusSync sync) {
        videoDocPort.syncStatus(videoId, sync);
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

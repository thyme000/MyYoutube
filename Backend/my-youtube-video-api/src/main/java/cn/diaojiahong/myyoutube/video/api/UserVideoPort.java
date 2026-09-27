package cn.diaojiahong.myyoutube.video.api;

import cn.diaojiahong.myyoutube.video.api.dto.VideoSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * video 服务对外契约: 用户侧视频查询(历史/点赞/关注状态)。
 * 拆部署前由 video 模块的 UserVideoPortImpl 提供本地实现;
 * 拆部署后走 Feign, 过渡期用 my-youtube.video-service.url 指向旧单体。
 */
@FeignClient(name = "my-youtube-video-service", path = "/internal/user-video",
        url = "${my-youtube.video-service.url:}")
public interface UserVideoPort {

    @PostMapping("/list-ordered")
    List<VideoSummary> listByIdsOrdered(@RequestBody Set<String> ids);

    @PostMapping("/liked-map")
    Map<Long, Boolean> likedMap(@RequestParam("userId") Long userId, @RequestBody Set<String> videoIds);

    @PostMapping("/followed-map")
    Map<Long, Boolean> followedMap(@RequestParam("userId") Long userId, @RequestBody Collection<Long> authorIds);
}

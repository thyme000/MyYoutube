package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.video.api.dto.VideoSummary;
import cn.diaojiahong.myyoutube.video.port.UserVideoPortImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * video 服务内部接口: 供 user 等服务经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal/user-video")
@RequiredArgsConstructor
public class InternalUserVideoController {

    private final UserVideoPortImpl userVideoPort;

    @PostMapping("/list-ordered")
    public List<VideoSummary> listByIdsOrdered(@RequestBody Set<String> ids) {
        return userVideoPort.listByIdsOrdered(ids);
    }

    @PostMapping("/liked-map")
    public Map<Long, Boolean> likedMap(@RequestParam("userId") Long userId, @RequestBody Set<String> videoIds) {
        return userVideoPort.likedMap(userId, videoIds);
    }

    @PostMapping("/followed-map")
    public Map<Long, Boolean> followedMap(@RequestParam("userId") Long userId, @RequestBody Collection<Long> authorIds) {
        return userVideoPort.followedMap(userId, authorIds);
    }
}

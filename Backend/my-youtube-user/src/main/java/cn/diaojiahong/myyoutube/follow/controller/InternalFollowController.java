package cn.diaojiahong.myyoutube.follow.controller;

import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * user 服务内部接口: 关注关系权威值, 供 video/chat/search 经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal/follow")
@RequiredArgsConstructor
public class InternalFollowController {

    private final IUserFollowService userFollowService;

    @GetMapping("/follow-ids")
    public List<Long> getFollowIds(@RequestParam("userId") Long userId) {
        return userFollowService.getFollowIds(userId);
    }

    @GetMapping("/is-follow")
    public Boolean isFollow(@RequestParam("userId") Long userId, @RequestParam("authorId") Long authorId) {
        return userFollowService.isFollow(userId, authorId);
    }

    @PostMapping("/follow-list")
    public List<Boolean> isFollowOnList(@RequestParam("userId") Long userId, @RequestBody List<Long> authorIds) {
        return userFollowService.isFollowOnList(authorIds, userId);
    }

    @PostMapping("/interrelated")
    public boolean interrelated(@RequestBody FollowPort.InterrelatedQuery query) {
        return userFollowService.interrelated(query.getUserId(), query.getMemberIds());
    }

    @GetMapping("/fans-count")
    public Long fansCount(@RequestParam("userId") Long userId) {
        return userFollowService.countFans(userId);
    }

    @GetMapping("/attention-count")
    public Long attentionCount(@RequestParam("userId") Long userId) {
        return userFollowService.countAttention(userId);
    }
}

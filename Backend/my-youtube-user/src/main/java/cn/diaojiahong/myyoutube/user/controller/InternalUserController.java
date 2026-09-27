package cn.diaojiahong.myyoutube.user.controller;

import cn.diaojiahong.myyoutube.user.api.dto.CounterAdjust;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.port.UserPortAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * user 服务内部接口: 供 video/chat/admin 等服务经 Feign 调用, 不经网关暴露
 */
@RestController
@RequestMapping("/internal/user")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserPortAdapter userPortAdapter;

    @GetMapping("/{id}")
    public UserSummary getUser(@PathVariable Long id) {
        return userPortAdapter.getUser(id);
    }

    @PostMapping("/list-by-ids")
    public List<UserSummary> listByIds(@RequestBody Collection<Long> ids) {
        return userPortAdapter.listByIds(ids);
    }

    @PostMapping("/nicknames")
    public Map<Long, String> getNicknames(@RequestBody Collection<Long> ids) {
        return userPortAdapter.getNicknames(ids);
    }

    @PostMapping("/avatars")
    public Map<Long, String> getAvatars(@RequestBody Collection<Long> ids) {
        return userPortAdapter.getAvatars(ids);
    }

    @PostMapping("/counters/adjust")
    public void adjustCounters(@RequestBody List<CounterAdjust> adjustments) {
        userPortAdapter.adjustCounters(adjustments);
    }
}

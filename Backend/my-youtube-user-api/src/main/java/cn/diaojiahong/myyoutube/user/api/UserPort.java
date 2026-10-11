package cn.diaojiahong.myyoutube.user.api;

import cn.diaojiahong.myyoutube.user.api.dto.CounterAdjust;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Collection;
import java.util.List;

/**
 * user 服务对外契约: 用户信息查询与计数调整。
 * 拆部署前由 user 模块的 UserPortAdapter 提供本地实现;
 * 拆部署后走 Feign, 过渡期用 my-youtube.user-service.url 指向旧单体。
 * 昵称/头像等单列值从 listByIds 的 UserSummary 里本地投影, 不再单设端点。
 */
@FeignClient(name = "my-youtube-user-service", contextId = "userPort", path = "/internal/user",
        url = "${my-youtube.user-service.url:}")
public interface UserPort {
    @org.springframework.web.bind.annotation.PostMapping("/counters/content-event")
    void applyContentCountEvent(@org.springframework.web.bind.annotation.RequestBody cn.diaojiahong.myyoutube.user.api.dto.ContentCountEvent event);


    @GetMapping("/{id}")
    UserSummary getUser(@PathVariable("id") Long id);

    @PostMapping("/list-by-ids")
    List<UserSummary> listByIds(@RequestBody Collection<Long> ids);

    /** 关注计数增减(fans/attentionCount) */
    @PostMapping("/counters/adjust")
    void adjustCounters(@RequestBody List<CounterAdjust> adjustments);
}

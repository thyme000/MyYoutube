package cn.diaojiahong.myyoutube.user.api;

import cn.diaojiahong.myyoutube.user.api.dto.ReceivedLikeUpdate;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Collection;
import java.util.List;

/**
 * user 服务对外契约: 用户信息查询。
 */
@FeignClient(name = "my-youtube-user-service", contextId = "userPort", path = "/internal/user",
        url = "${my-youtube.user-service.url:}")
public interface UserPort {
    @GetMapping("/{id}")
    UserSummary getUser(@PathVariable("id") Long id);

    @PostMapping("/list-by-ids")
    List<UserSummary> listByIds(@RequestBody Collection<Long> ids);

    /**
     * 批量用作者获赞绝对值覆盖 user.like_count。
     * 返回本批成功接受的 userId；用户不存在的不在列表中。
     * 已收下相同或更新的版本时不改计数，但仍算接受。
     */
    @PostMapping("/received-likes")
    List<Long> overwriteReceivedLikes(@RequestBody List<ReceivedLikeUpdate> updates);
}

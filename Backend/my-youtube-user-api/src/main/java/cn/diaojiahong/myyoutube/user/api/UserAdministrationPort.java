package cn.diaojiahong.myyoutube.user.api;

import cn.diaojiahong.myyoutube.common.feign.InternalTokenFeignConfiguration;
import cn.diaojiahong.myyoutube.common.feign.PropagateBusinessErrors;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "my-youtube-user-service", contextId = "userAdministrationPort",
        path = "/internal/user/administrators", url = "${my-youtube.user-service.url:}",
        configuration = InternalTokenFeignConfiguration.class)
@PropagateBusinessErrors({ErrorCode.INVALID_ARGUMENT, ErrorCode.NOT_FOUND,
        ErrorCode.STATE_CONFLICT, ErrorCode.SUPER_ADMIN_REQUIRED})
public interface UserAdministrationPort {
    record Query(Long actorId, int pageNo, int pageSize) {}
    record Administrator(Long userId, String username, String nickname, UserRole role) {}
    record Change(Long userId, UserRole role, boolean changed) {}

    @PostMapping("/page")
    PageResult<Administrator> page(@RequestBody Query query);

    @PutMapping("/{userId}")
    Change grant(@PathVariable("userId") Long userId, @RequestParam("actorId") Long actorId);

    @DeleteMapping("/{userId}")
    Change revoke(@PathVariable("userId") Long userId, @RequestParam("actorId") Long actorId);
}

package cn.diaojiahong.myyoutube.user.api;

import cn.diaojiahong.myyoutube.common.feign.PropagateBusinessErrors;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.feign.InternalTokenFeignConfiguration;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "my-youtube-user-service", contextId = "userRolePort", path = "/internal/user/roles",
        url = "${my-youtube.user-service.url:}", configuration = InternalTokenFeignConfiguration.class)
public interface UserRolePort {
    @PropagateBusinessErrors(ErrorCode.NOT_FOUND)
    @GetMapping("/{userId}")
    UserRole getRole(@PathVariable("userId") Long userId);
}

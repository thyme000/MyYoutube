package cn.diaojiahong.myyoutube.user.controller;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import cn.diaojiahong.myyoutube.user.port.UserRolePortAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CurrentUserRoleController {
    private final UserRolePortAdapter roles;

    @GetMapping("/user/role")
    public ResponseEntity<Result<UserRole>> role() {
        UserRole role = roles.getRole(UserContext.requireUserId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(role));
    }
}

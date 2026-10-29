package cn.diaojiahong.myyoutube.user.controller;

import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import cn.diaojiahong.myyoutube.user.port.UserRolePortAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/user/roles")
@RequiredArgsConstructor
public class InternalUserRoleController {
    private final UserRolePortAdapter roles;
    private final InternalApiCredentials credentials;

    @GetMapping("/{userId}")
    public UserRole getRole(@PathVariable Long userId,
                            @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return roles.getRole(userId);
    }
}

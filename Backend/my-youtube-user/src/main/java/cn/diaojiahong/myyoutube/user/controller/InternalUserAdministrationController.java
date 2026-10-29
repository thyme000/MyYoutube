package cn.diaojiahong.myyoutube.user.controller;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.user.api.UserAdministrationPort.*;
import cn.diaojiahong.myyoutube.user.service.UserAdministrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/user/administrators")
@RequiredArgsConstructor
public class InternalUserAdministrationController {
    private final UserAdministrationService administration;
    private final InternalApiCredentials credentials;

    @PostMapping("/page")
    public PageResult<Administrator> page(@RequestBody Query query,
            @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return administration.page(query);
    }

    @PutMapping("/{userId}")
    public Change grant(@PathVariable Long userId, @RequestParam Long actorId,
            @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return administration.grant(userId, actorId);
    }

    @DeleteMapping("/{userId}")
    public Change revoke(@PathVariable Long userId, @RequestParam Long actorId,
            @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return administration.revoke(userId, actorId);
    }
}

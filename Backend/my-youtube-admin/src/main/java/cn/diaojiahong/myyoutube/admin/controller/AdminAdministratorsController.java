package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.user.api.UserAdministrationPort;
import cn.diaojiahong.myyoutube.user.api.UserAdministrationPort.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/administrators")
@RequiredArgsConstructor
public class AdminAdministratorsController {
    private final UserAdministrationPort administration;
    private final AdminAuthorization authorization;

    @GetMapping
    public Result<PageResult<Administrator>> page(@ModelAttribute PageQuery query) {
        Long actorId = authorization.requireSuperAdmin();
        query.validate();

        return Result.success(administration.page(new Query(actorId, query.getPageNo(), query.getPageSize())));
    }

    @PutMapping("/{userId}")
    public Result<Change> grant(@PathVariable Long userId) {
        Long actorId = authorization.requireSuperAdmin();
        return Result.success(administration.grant(userId, actorId));
    }

    @DeleteMapping("/{userId}")
    public Result<Change> revoke(@PathVariable Long userId) {
        Long actorId = authorization.requireSuperAdmin();
        return Result.success(administration.revoke(userId, actorId));
    }
}

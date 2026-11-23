package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import cn.diaojiahong.myyoutube.common.counter.CounterRepairRequest;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.user.api.UserCounterAdminPort;
import cn.diaojiahong.myyoutube.content.api.ContentCounterAdminPort;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/counter-drifts")
@RequiredArgsConstructor
public class AdminCounterDriftController {

    private final ContentCounterAdminPort contents;
    private final UserCounterAdminPort users;
    private final AdminAuthorization authorization;

    @GetMapping
    public Result<CursorPage<CounterDrift, Long>> drifts(@RequestParam("type") String type,
                                                          @RequestParam(value = "cursor", required = false) Long cursor,
                                                          @RequestParam(value = "size", defaultValue = "20") int size) {
        authorization.requireAdmin();
        CounterType counterType = CounterType.fromCode(type);
        CursorPage<CounterDrift, Long> page = switch (counterType) {
            case CONTENT_LIKE, COMMENT_LIKE, CONTENT_COLLECT, CONTENT_SHARE ->
                    contents.drifts(counterType.code(), cursor, size);
            case USER_FANS, USER_ATTENTION -> users.drifts(counterType.code(), cursor, size);
        };
        return Result.success(page);
    }

    @PostMapping("/repair")
    public Result<Integer> repair(@RequestBody CounterRepairRequest request) {
        authorization.requireAdmin();
        CounterType counterType = CounterType.fromCode(request.type());
        int repaired = switch (counterType) {
            case CONTENT_LIKE, COMMENT_LIKE, CONTENT_COLLECT, CONTENT_SHARE -> contents.repair(request);
            case USER_FANS, USER_ATTENTION -> users.repair(request);
        };
        return Result.success(repaired);
    }
}

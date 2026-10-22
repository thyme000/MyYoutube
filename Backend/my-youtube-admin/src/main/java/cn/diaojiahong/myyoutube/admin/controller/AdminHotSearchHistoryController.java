package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort.*;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/hot-search")
@RequiredArgsConstructor
public class AdminHotSearchHistoryController {
    private final HotSearchHistoryPort port;

    @GetMapping("/dates")
    public Page<Day> dates(@RequestParam(value = "cursor", required = false) String cursor,
                           @RequestParam(value = "size", defaultValue = "20") Integer size) {
        requireAdmin();
        return port.dates(cursor, size);
    }

    @GetMapping("/groups")
    public Page<Group> groups(@RequestParam("date") String date,
                              @RequestParam(value = "cursor", required = false) String cursor,
                              @RequestParam(value = "size", defaultValue = "20") Integer size) {
        requireAdmin();
        return port.groups(date, cursor, size);
    }

    @GetMapping("/keywords")
    public Page<Keyword> keywords(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                                  @RequestParam(value = "cursor", required = false) String cursor,
                                  @RequestParam(value = "size", defaultValue = "20") Integer size) {
        requireAdmin();
        return port.keywords(date, groupId, cursor, size);
    }

    @GetMapping("/users")
    public Page<User> users(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                            @RequestParam("keywordId") Long keywordId,
                            @RequestParam(value = "cursor", required = false) String cursor,
                            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        requireAdmin();
        return port.users(date, groupId, keywordId, cursor, size);
    }

    @GetMapping("/events")
    public Page<Event> events(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                              @RequestParam("keywordId") Long keywordId,
                              @RequestParam(value = "userId", required = false) Long userId,
                              @RequestParam(value = "cursor", required = false) String cursor,
                              @RequestParam(value = "size", defaultValue = "20") Integer size) {
        requireAdmin();
        return port.events(date, groupId, keywordId, userId, cursor, size);
    }

    private void requireAdmin() {
        if (!"1".equals(UserContext.getUserId())) throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限");
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Result<?>> remoteFailure(FeignException error) {
        ErrorCode code = error.status() == 400 ? ErrorCode.INVALID_ARGUMENT
                : error.status() == 503 ? ErrorCode.DEPENDENCY_UNAVAILABLE : ErrorCode.DEPENDENCY_FAILURE;
        int status = code.getHttpStatus();
        return ResponseEntity.status(status).body(Result.fail(code,
                status == 400 ? "历史查询参数无效" : "热搜历史服务暂时不可用"));
    }
}

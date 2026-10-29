package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/hot-search")
@RequiredArgsConstructor
public class AdminHotSearchHistoryController {
    private final HotSearchHistoryPort port;
    private final AdminAuthorization authorization;

    @GetMapping("/dates")
    public Result<CursorPage<Day, String>> dates(@RequestParam(value = "cursor", required = false) String cursor,
                           @RequestParam(value = "size", defaultValue = "20") Integer size) {
        authorization.requireAdmin();

        return Result.success(port.dates(cursor, size));
    }

    @GetMapping("/groups")
    public Result<CursorPage<Group, String>> groups(@RequestParam("date") String date,
                              @RequestParam(value = "cursor", required = false) String cursor,
                              @RequestParam(value = "size", defaultValue = "20") Integer size) {
        authorization.requireAdmin();

        return Result.success(port.groups(date, cursor, size));
    }

    @GetMapping("/keywords")
    public Result<CursorPage<Keyword, String>> keywords(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                                  @RequestParam(value = "cursor", required = false) String cursor,
                                  @RequestParam(value = "size", defaultValue = "20") Integer size) {
        authorization.requireAdmin();

        return Result.success(port.keywords(date, groupId, cursor, size));
    }

    @GetMapping("/users")
    public Result<CursorPage<User, String>> users(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                            @RequestParam("keywordId") Long keywordId,
                            @RequestParam(value = "cursor", required = false) String cursor,
                            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        authorization.requireAdmin();

        return Result.success(port.users(date, groupId, keywordId, cursor, size));
    }

    @GetMapping("/events")
    public Result<CursorPage<Event, String>> events(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                              @RequestParam("keywordId") Long keywordId,
                              @RequestParam(value = "userId", required = false) Long userId,
                              @RequestParam(value = "cursor", required = false) String cursor,
                              @RequestParam(value = "size", defaultValue = "20") Integer size) {
        authorization.requireAdmin();

        return Result.success(port.events(date, groupId, keywordId, userId, cursor, size));
    }


}

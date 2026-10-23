package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort.*;
import cn.diaojiahong.myyoutube.search.hot.HotSearchHistoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/hot-search/history")
@RequiredArgsConstructor
public class InternalHotSearchHistoryController {
    private final HotSearchHistoryQueryService service;

    @GetMapping("/dates")
    public CursorPage<Day, String> dates(@RequestParam(value = "cursor", required = false) String cursor,
                           @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return service.dates(cursor, size);
    }

    @GetMapping("/groups")
    public CursorPage<Group, String> groups(@RequestParam("date") String date,
                              @RequestParam(value = "cursor", required = false) String cursor,
                              @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return service.groups(date, cursor, size);
    }

    @GetMapping("/keywords")
    public CursorPage<Keyword, String> keywords(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                                  @RequestParam(value = "cursor", required = false) String cursor,
                                  @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return service.keywords(date, groupId, cursor, size);
    }

    @GetMapping("/users")
    public CursorPage<User, String> users(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                            @RequestParam("keywordId") Long keywordId,
                            @RequestParam(value = "cursor", required = false) String cursor,
                            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return service.users(date, groupId, keywordId, cursor, size);
    }

    @GetMapping("/events")
    public CursorPage<Event, String> events(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                              @RequestParam("keywordId") Long keywordId,
                              @RequestParam(value = "userId", required = false) Long userId,
                              @RequestParam(value = "cursor", required = false) String cursor,
                              @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return service.events(date, groupId, keywordId, userId, cursor, size);
    }
}

package cn.diaojiahong.myyoutube.search.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@FeignClient(name = "my-youtube-search-service", contextId = "hotSearchHistoryPort",
        path = "/internal/hot-search/history", url = "${my-youtube.search-service.url:}")
public interface HotSearchHistoryPort {
    record Page<T>(List<T> items, String nextCursor) {}
    record Day(LocalDate statDate, LocalDateTime updatedAt) {}
    record Group(String groupId, String groupName, String vectorVersion, long searchCount,
                 long userCount, long score, long rankNo, LocalDateTime updatedAt) {}
    // keywordId 是该原词在当前日期/组下的首条记录 ID，后续查询不在 URL 中传原文。
    record Keyword(long keywordId, String rawKeyword, long searchCount, long userCount) {}
    record User(long userId, long searchCount, long firstHappenedAt, long lastHappenedAt) {}
    record Event(long id, String eventId, LocalDate statDate, long happenedAt, long eventHour, long userId,
                 String rawKeyword, String groupingKeyword, String groupId, String groupName, String vectorVersion) {}

    @GetMapping("/dates")
    Page<Day> dates(@RequestParam(value = "cursor", required = false) String cursor,
                    @RequestParam(value = "size", defaultValue = "20") Integer size);

    @GetMapping("/groups")
    Page<Group> groups(@RequestParam("date") String date,
                       @RequestParam(value = "cursor", required = false) String cursor,
                       @RequestParam(value = "size", defaultValue = "20") Integer size);

    @GetMapping("/keywords")
    Page<Keyword> keywords(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                           @RequestParam(value = "cursor", required = false) String cursor,
                           @RequestParam(value = "size", defaultValue = "20") Integer size);

    @GetMapping("/users")
    Page<User> users(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                     @RequestParam("keywordId") Long keywordId,
                     @RequestParam(value = "cursor", required = false) String cursor,
                     @RequestParam(value = "size", defaultValue = "20") Integer size);

    @GetMapping("/events")
    Page<Event> events(@RequestParam("date") String date, @RequestParam("groupId") String groupId,
                       @RequestParam("keywordId") Long keywordId,
                       @RequestParam(value = "userId", required = false) Long userId,
                       @RequestParam(value = "cursor", required = false) String cursor,
                       @RequestParam(value = "size", defaultValue = "20") Integer size);
}

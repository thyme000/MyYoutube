package cn.diaojiahong.myyoutube.search.api;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "my-youtube-search-service", contextId = "contentVectorDeadLetterPort",
        path = "/internal/dead-letters/content-vectors", url = "${my-youtube.search-service.url:}")
public interface ContentVectorDeadLetterPort {
    enum State { UNRECOVERED, RECOVERED, INELIGIBLE, UNKNOWN, INVALID_MESSAGE }
    enum ReplayStatus { ACCEPTED, ALREADY_COMPLETED, INELIGIBLE, DISABLED, NOT_FOUND,
        INVALID_MESSAGE, STATE_UNAVAILABLE, DELIVERY_UNCONFIRMED }

    record Reference(String brokerName, Integer queueId, Long offset) {}
    record Item(Reference reference, String messageId, Long contentId, long storedAt, int reconsumeTimes,
                State state, String reason, String vectorVersion, String targetVersion, String failureReason) {}
    record Page(List<Item> items, String nextCursor, boolean enabled) {}
    record ReplayResult(ReplayStatus status, Long contentId, String message) {}

    @GetMapping("/messages")
    Page query(@RequestParam(value = "cursor", required = false) String cursor,
               @RequestParam(value = "size", defaultValue = "20") Integer size);

    @PostMapping("/messages/replay")
    ReplayResult replay(@RequestBody Reference reference, @RequestParam("actorId") Long actorId);
}

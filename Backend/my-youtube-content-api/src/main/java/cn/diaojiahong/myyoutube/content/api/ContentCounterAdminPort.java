package cn.diaojiahong.myyoutube.content.api;

import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import cn.diaojiahong.myyoutube.common.counter.CounterRepairRequest;
import cn.diaojiahong.myyoutube.common.feign.PropagateBusinessErrors;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 内容服务对外契约: 作品赞、评论赞、收藏、分享的漂移清单和覆盖。
 */
@PropagateBusinessErrors(ErrorCode.INVALID_ARGUMENT)
@FeignClient(name = "my-youtube-content-service", contextId = "contentCounterAdminPort",
        path = ContentCounterAdminPort.BASE_PATH, url = "${my-youtube.content-service.url:}")
public interface ContentCounterAdminPort {

    String BASE_PATH = "/internal/admin/counters";

    @GetMapping("/drifts")
    CursorPage<CounterDrift, Long> drifts(@RequestParam("type") String type,
                                           @RequestParam(value = "cursor", required = false) Long cursor,
                                           @RequestParam("size") int size);

    @PostMapping("/repair")
    int repair(@RequestBody CounterRepairRequest request);
}

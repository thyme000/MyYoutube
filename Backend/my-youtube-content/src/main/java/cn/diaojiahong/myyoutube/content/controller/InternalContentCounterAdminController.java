package cn.diaojiahong.myyoutube.content.controller;

import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import cn.diaojiahong.myyoutube.common.counter.CounterRepairRequest;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.counter.ContentCounterAdminService;
import cn.diaojiahong.myyoutube.content.api.ContentCounterAdminPort;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容服务内部接口: 管理端计数漂移清单和覆盖。
 */
@RestController
@RequestMapping(ContentCounterAdminPort.BASE_PATH)
@RequiredArgsConstructor
public class InternalContentCounterAdminController implements ContentCounterAdminPort {

    private final ContentCounterAdminService counters;

    @Override
    @GetMapping("/drifts")
    public CursorPage<CounterDrift, Long> drifts(@RequestParam("type") String type,
                                                  @RequestParam(value = "cursor", required = false) Long cursor,
                                                  @RequestParam("size") int size) {
        return counters.drifts(CounterType.fromCode(type), cursor, size);
    }

    @Override
    @PostMapping("/repair")
    public int repair(@RequestBody CounterRepairRequest request) {
        return counters.repair(CounterType.fromCode(request.type()), request.ids());
    }
}

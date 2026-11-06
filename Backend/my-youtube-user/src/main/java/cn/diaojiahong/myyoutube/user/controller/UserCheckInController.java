package cn.diaojiahong.myyoutube.user.controller;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInHeatmapVO;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInResultVO;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInStatusVO;
import cn.diaojiahong.myyoutube.user.service.UserCheckInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/user/check-ins")
@Tag(name = "用户签到")
public class UserCheckInController {
    private final UserCheckInService service;

    @Operation(summary = "手动签到", description = "按北京时间签到，同日重复请求提示今天已签到")
    @PostMapping
    public ResponseEntity<Result<CheckInResultVO>> checkIn() {
        CheckInResultVO result = service.checkIn(UserContext.requireUserId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(result));
    }

    @Operation(summary = "查询今日签到状态和本年签到天数")
    @GetMapping("/status")
    public ResponseEntity<Result<CheckInStatusVO>> status() {
        CheckInStatusVO status = service.status(UserContext.requireUserId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(status));
    }

    @Operation(summary = "查询指定自然年的签到日期和年内签到天数")
    @GetMapping("/heatmap")
    public ResponseEntity<Result<CheckInHeatmapVO>> heatmap(@RequestParam int year) {
        CheckInHeatmapVO heatmap = service.heatmap(UserContext.requireUserId(), year);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(heatmap));
    }
}

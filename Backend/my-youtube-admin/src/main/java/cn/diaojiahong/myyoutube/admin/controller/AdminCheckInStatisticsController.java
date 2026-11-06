package cn.diaojiahong.myyoutube.admin.controller;

import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Day;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Monthly;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Yearly;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/check-ins")
public class AdminCheckInStatisticsController {
    private final UserCheckInStatisticsPort statistics;
    private final AdminAuthorization authorization;

    @GetMapping("/daily")
    public Result<List<Day>> daily(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        authorization.requireAdmin();
        return Result.success(statistics.daily(from, to));
    }

    @GetMapping("/monthly")
    public Result<Monthly> monthly(@RequestParam int year, @RequestParam int month) {
        authorization.requireAdmin();
        return Result.success(statistics.monthly(year, month));
    }

    @GetMapping("/yearly")
    public Result<Yearly> yearly(@RequestParam int year) {
        authorization.requireAdmin();
        return Result.success(statistics.yearly(year));
    }
}

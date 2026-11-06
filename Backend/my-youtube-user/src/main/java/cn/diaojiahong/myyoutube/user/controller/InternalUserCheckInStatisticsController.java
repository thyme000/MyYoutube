package cn.diaojiahong.myyoutube.user.controller;

import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Day;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Monthly;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Yearly;
import cn.diaojiahong.myyoutube.user.service.UserCheckInStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/user/check-ins")
public class InternalUserCheckInStatisticsController {
    private final UserCheckInStatisticsService statistics;
    private final InternalApiCredentials credentials;

    @GetMapping("/daily")
    public List<Day> daily(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return statistics.daily(from, to);
    }

    @GetMapping("/monthly")
    public Monthly monthly(@RequestParam int year, @RequestParam int month,
                           @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return statistics.monthly(year, month);
    }

    @GetMapping("/yearly")
    public Yearly yearly(@RequestParam int year,
                         @RequestHeader(value = AuthConstants.INTERNAL_TOKEN_HEADER, required = false) String token) {
        credentials.require(token);
        return statistics.yearly(year);
    }
}

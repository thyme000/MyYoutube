package cn.diaojiahong.myyoutube.user.api;

import cn.diaojiahong.myyoutube.common.feign.InternalTokenFeignConfiguration;
import cn.diaojiahong.myyoutube.common.feign.PropagateBusinessErrors;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

@FeignClient(name = "my-youtube-user-service", contextId = "userCheckInStatisticsPort",
        path = "/internal/user/check-ins", url = "${my-youtube.user-service.url:}",
        configuration = InternalTokenFeignConfiguration.class)
@PropagateBusinessErrors(ErrorCode.INVALID_ARGUMENT)
public interface UserCheckInStatisticsPort {
    record Day(LocalDate statDate, long signedUsers) {
    }

    /** 所选区间内的去重人数和签到人次。互不重叠的区间可以相加签到人次。 */
    record Totals(long signedUsers, long checkIns) {
    }

    /** 自然月内的去重人数和签到人次。年度人数按全年独立去重。 */
    record Month(int month, long signedUsers, long checkIns) {
    }

    record Monthly(int year, int month, LocalDate throughDate, Totals totals, List<Day> daily,
                   double averageDailyUsers, Day peakDay) {
    }

    /** 月份序列从一月到 throughDate 所在月份，无签到的已开始月份计零。 */
    record Yearly(int year, LocalDate throughDate, Totals totals, List<Month> monthly) {
    }

    @GetMapping("/daily")
    List<Day> daily(@RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to);

    @GetMapping("/monthly")
    Monthly monthly(@RequestParam("year") int year, @RequestParam("month") int month);

    @GetMapping("/yearly")
    Yearly yearly(@RequestParam("year") int year);
}

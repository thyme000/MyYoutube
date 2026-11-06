package cn.diaojiahong.myyoutube.user.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Day;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Month;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Monthly;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Totals;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Yearly;
import cn.diaojiahong.myyoutube.user.mapper.UserCheckInMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserCheckInStatisticsService {
    private static final int MAX_DAILY_RANGE = 366;

    private final UserCheckInMapper mapper;

    public List<Day> daily(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.now().toLocalDate();
        validateRange(from, to, today);
        return dailySeries(from, to);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Monthly monthly(int year, int month) {
        LocalDate today = BusinessTime.now().toLocalDate();
        validateYear(year, today);
        if (month < 1 || month > 12) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "统计月份须在 1 至 12 之间");
        }

        YearMonth period = YearMonth.of(year, month);
        LocalDate start = period.atDay(1);
        if (start.isAfter(today)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "统计月份不能晚于当前月份");
        }

        LocalDate end = period.atEndOfMonth();
        if (end.isAfter(today)) {
            end = today;
        }

        Totals totals = mapper.countPeriod(start, end);
        List<Day> daily = dailySeries(start, end);
        Day peak = null;
        for (Day day : daily) {
            if (day.signedUsers() > 0 && (peak == null || day.signedUsers() > peak.signedUsers())) {
                peak = day;
            }
        }
        double average = (double) totals.checkIns() / daily.size();
        return new Monthly(year, month, end, totals, daily, average, peak);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Yearly yearly(int year) {
        LocalDate today = BusinessTime.now().toLocalDate();
        validateYear(year, today);
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = year == today.getYear() ? today : LocalDate.of(year, 12, 31);

        Totals totals = mapper.countPeriod(start, end);
        Map<Integer, Month> byMonth = new HashMap<>();
        for (Month month : mapper.countByMonth(start, end)) {
            byMonth.put(month.month(), month);
        }

        List<Month> monthly = new ArrayList<>();
        for (int month = 1; month <= end.getMonthValue(); month++) {
            monthly.add(byMonth.getOrDefault(month, new Month(month, 0, 0)));
        }
        return new Yearly(year, end, totals, monthly);
    }

    private List<Day> dailySeries(LocalDate start, LocalDate end) {
        Map<LocalDate, Long> byDate = new HashMap<>();
        for (Day day : mapper.countByDay(start, end)) {
            byDate.put(day.statDate(), day.signedUsers());
        }

        List<Day> daily = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            daily.add(new Day(date, byDate.getOrDefault(date, 0L)));
        }
        return daily;
    }

    private static void validateRange(LocalDate start, LocalDate end, LocalDate today) {
        if (end.isBefore(start)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "统计开始日期不能晚于结束日期");
        }
        if (end.isAfter(today)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "统计结束日期不能晚于今天");
        }

        validateYear(start.getYear(), today);
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAILY_RANGE) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "统计日期范围最多 %d 天".formatted(MAX_DAILY_RANGE));
        }
    }

    private static void validateYear(int year, LocalDate today) {
        int earliestYear = UserCheckInService.MYSQL_DATE_MIN_YEAR;
        int currentYear = today.getYear();
        if (year < earliestYear || year > currentYear) {
            String message = "统计年份须在 %d 至 %d 之间".formatted(earliestYear, currentYear);
            throw new BizException(ErrorCode.INVALID_ARGUMENT, message);
        }
    }
}

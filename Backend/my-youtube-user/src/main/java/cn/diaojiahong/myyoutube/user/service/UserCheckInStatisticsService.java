package cn.diaojiahong.myyoutube.user.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Day;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Month;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Monthly;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Totals;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Yearly;
import cn.diaojiahong.myyoutube.user.domain.po.UserCheckInMonthSnapshot;
import cn.diaojiahong.myyoutube.user.mapper.UserCheckInMapper;
import cn.diaojiahong.myyoutube.user.mapper.UserCheckInMapper.DateRange;
import cn.diaojiahong.myyoutube.user.mapper.UserCheckInMonthSnapshotMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
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
    private final UserCheckInMonthSnapshotMapper snapshots;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Day> daily(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.now().toLocalDate();
        validateRange(from, to, today);
        return dailySeries(from, to, loadSnapshots(from, to, today));
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

        UserCheckInMonthSnapshot snapshot = period.isBefore(YearMonth.from(today))
                ? snapshots.selectById(start)
                : null;
        Totals totals;
        List<Day> daily;
        if (snapshot == null) {
            totals = mapper.countPeriod(start, end);
            daily = dailySeries(start, end, Map.of());
        } else {
            totals = new Totals(snapshot.getSignedUsers(), snapshot.getCheckIns());
            daily = dailySeries(start, end, Map.of(start, snapshot));
        }

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

        Map<LocalDate, UserCheckInMonthSnapshot> archived = loadSnapshots(start, end, today);
        UserCheckInMonthSnapshot december = archived.get(LocalDate.of(year, java.time.Month.DECEMBER, 1));
        Totals totals = december == null
                ? mapper.countPeriod(start, end)
                : new Totals(december.getYearSignedUsers(), december.getYearCheckIns());

        Map<Integer, Month> byMonth = new HashMap<>();
        for (UserCheckInMonthSnapshot snapshot : archived.values()) {
            int month = snapshot.getMonthStart().getMonthValue();
            byMonth.put(month, new Month(month, snapshot.getSignedUsers(), snapshot.getCheckIns()));
        }
        List<DateRange> gaps = missingRanges(start, end, archived);
        if (!gaps.isEmpty()) {
            for (Month month : mapper.countByMonth(gaps)) {
                byMonth.put(month.month(), month);
            }
        }

        List<Month> monthly = new ArrayList<>();
        for (int month = 1; month <= end.getMonthValue(); month++) {
            monthly.add(byMonth.getOrDefault(month, new Month(month, 0, 0)));
        }
        return new Yearly(year, end, totals, monthly);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void archiveMonth(YearMonth month) {
        LocalDate today = BusinessTime.now().toLocalDate();
        if (!month.isBefore(YearMonth.from(today))) {
            throw new IllegalStateException("只能归档已结束的签到月份");
        }

        LocalDate start = month.atDay(1);
        if (snapshots.selectById(start) != null) {
            return;
        }

        LocalDate end = month.atEndOfMonth();
        Totals monthTotals = mapper.countPeriod(start, end);
        List<Long> dailyCounts = dailySeries(start, end, Map.of()).stream().map(Day::signedUsers).toList();

        // 一条完整快照既保存统计结果，也标记本月计算完成。
        var snapshot = new UserCheckInMonthSnapshot()
                .setMonthStart(start)
                .setSignedUsers(monthTotals.signedUsers())
                .setCheckIns(monthTotals.checkIns())
                .setDailyCounts(dailyCounts);
        // 年报只在全年结束后读取快照，因此只在归档 12 月时做全年去重。
        if (month.getMonth() == java.time.Month.DECEMBER) {
            Totals yearTotals = mapper.countPeriod(start.withDayOfYear(1), end);
            snapshot.setYearSignedUsers(yearTotals.signedUsers()).setYearCheckIns(yearTotals.checkIns());
        }

        try {
            snapshots.insert(snapshot);
        } catch (DuplicateKeyException ignored) {
            // 锁租期可能先于统计结束，主键冲突说明另一个实例已提交本月快照。
        }
    }

    private List<Day> dailySeries(LocalDate start, LocalDate end, Map<LocalDate, UserCheckInMonthSnapshot> archived) {
        Map<LocalDate, Long> byDate = new HashMap<>();
        List<DateRange> gaps = missingRanges(start, end, archived);
        if (!gaps.isEmpty()) {
            for (Day day : mapper.countByDay(gaps)) {
                byDate.put(day.statDate(), day.signedUsers());
            }
        }

        List<Day> daily = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            UserCheckInMonthSnapshot snapshot = archived.get(date.withDayOfMonth(1));
            long signedUsers = snapshot == null
                    ? byDate.getOrDefault(date, 0L)
                    : snapshot.getDailyCounts().get(date.getDayOfMonth() - 1);
            daily.add(new Day(date, signedUsers));
        }
        return daily;
    }

    private Map<LocalDate, UserCheckInMonthSnapshot> loadSnapshots(LocalDate start, LocalDate end, LocalDate today) {
        LocalDate first = start.withDayOfMonth(1);
        LocalDate last = end.withDayOfMonth(1);
        LocalDate currentMonth = today.withDayOfMonth(1);
        if (last.equals(currentMonth)) {
            last = currentMonth.minusMonths(1);
        }
        if (last.isBefore(first)) {
            return Map.of();
        }

        Map<LocalDate, UserCheckInMonthSnapshot> archived = new HashMap<>();
        for (UserCheckInMonthSnapshot snapshot : snapshots.selectList(Wrappers.<UserCheckInMonthSnapshot>lambdaQuery()
                .between(UserCheckInMonthSnapshot::getMonthStart, first, last))) {
            archived.put(snapshot.getMonthStart(), snapshot);
        }
        return archived;
    }

    /** 合并连续缺失月份，实时查询只扫描快照未覆盖的日期范围。 */
    private static List<DateRange> missingRanges(LocalDate start, LocalDate end,
                                                 Map<LocalDate, UserCheckInMonthSnapshot> archived) {
        List<DateRange> ranges = new ArrayList<>();
        LocalDate uncovered = start;
        for (LocalDate month = start.withDayOfMonth(1); !month.isAfter(end); month = month.plusMonths(1)) {
            if (!archived.containsKey(month)) {
                continue;
            }
            if (month.isAfter(uncovered)) {
                ranges.add(new DateRange(uncovered, month.minusDays(1)));
            }
            uncovered = month.plusMonths(1);
        }
        if (!uncovered.isAfter(end)) {
            ranges.add(new DateRange(uncovered, end));
        }
        return ranges;
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

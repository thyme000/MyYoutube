package cn.diaojiahong.myyoutube.user.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.user.cache.UserCheckInBitmapCache;
import cn.diaojiahong.myyoutube.user.domain.po.UserCheckIn;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInHeatmapVO;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInResultVO;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInStatusVO;
import cn.diaojiahong.myyoutube.user.mapper.UserCheckInMapper;
import cn.diaojiahong.myyoutube.user.mapper.UserCheckInMapper.YearSummary;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserCheckInService {
    // MySQL DATE 支持的最早日期是 1000-01-01。
    private static final int MYSQL_DATE_MIN_YEAR = 1000;

    private final UserCheckInMapper mapper;
    private final UserCheckInBitmapCache bitmapCache;

    public CheckInResultVO checkIn(Long userId) {
        LocalDateTime now = BusinessTime.now().withNano(0);
        LocalDate today = now.toLocalDate();
        UserCheckIn entry = new UserCheckIn().setUserId(userId).setCheckInDate(today).setCheckedInAt(now);

        try {
            mapper.insert(entry);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "今天已签到");
        }

        // 单条 INSERT 已提交，缓存失败不影响签到结果。
        bitmapCache.record(userId, today, () -> loadDates(userId, today.withDayOfYear(1)));
        return new CheckInResultVO(today);
    }

    public CheckInStatusVO status(Long userId) {
        LocalDate today = BusinessTime.now().toLocalDate();
        LocalDate yearStart = today.withDayOfYear(1);

        // 状态用于确认签到结果，直接读明细以避开 Redis 更新失败留下的旧位图。
        YearSummary summary = mapper.summarizeYear(userId, yearStart, today);
        return new CheckInStatusVO(today, today.equals(summary.latestDate()), summary.daysInYear());
    }

    public CheckInHeatmapVO heatmap(Long userId, int year) {
        int currentYear = BusinessTime.now().getYear();
        if (year < MYSQL_DATE_MIN_YEAR || year > currentYear) {
            String message = "签到年份须在 %d 至 %d 之间".formatted(MYSQL_DATE_MIN_YEAR, currentYear);
            throw new BizException(ErrorCode.INVALID_ARGUMENT, message);
        }

        LocalDate start = LocalDate.of(year, 1, 1);
        List<LocalDate> dates = year == currentYear
                ? bitmapCache.dates(userId, start, () -> loadDates(userId, start))
                : loadDates(userId, start);
        return new CheckInHeatmapVO(year, dates, dates.size());
    }

    private List<LocalDate> loadDates(Long userId, LocalDate start) {
        return mapper.selectList(Wrappers.<UserCheckIn>lambdaQuery()
                        .select(UserCheckIn::getCheckInDate)
                        .eq(UserCheckIn::getUserId, userId)
                        .ge(UserCheckIn::getCheckInDate, start)
                        .lt(UserCheckIn::getCheckInDate, start.plusYears(1))
                        .orderByAsc(UserCheckIn::getCheckInDate))
                .stream()
                .map(UserCheckIn::getCheckInDate)
                .toList();
    }
}

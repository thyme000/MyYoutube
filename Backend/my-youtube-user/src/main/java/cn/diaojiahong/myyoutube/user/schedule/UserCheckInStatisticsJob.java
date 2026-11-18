package cn.diaojiahong.myyoutube.user.schedule;

import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.user.service.UserCheckInStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

@Component
@RequiredArgsConstructor
public class UserCheckInStatisticsJob {
    public static final String LOCK_NAME = "schedule:lock:user:check-in:statistics";

    private final ScheduleRoundLock roundLock;
    private final UserCheckInStatisticsService statistics;

    @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Shanghai")
    public void archivePreviousMonth() {
        roundLock.run(LOCK_NAME, () -> {
            YearMonth previousMonth = YearMonth.from(BusinessTime.now()).minusMonths(1);
            statistics.archiveMonth(previousMonth);
        });
    }
}

package cn.diaojiahong.myyoutube.user.schedule;

import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * user 域定时任务: 粉丝数与关注数的落库和对账。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleJob {

    private final CounterFlushService counterFlushService;
    private final ScheduleRoundLock roundLock;

    /**
     * 每 5s 把粉丝数、关注数的缓冲落库。上一轮结束后再等，避免慢轮次叠在一起。
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void flushUserCounters() {
        roundLock.run(UserScheduleLocks.COUNTER_FLUSH, counterFlushService::flushRound);
    }

    /**
     * 每小时用 COUNT(*) 覆盖一次粉丝数和关注数。
     */
    @Scheduled(cron = "0 40 * * * ?")
    public void reconcileUserCounters() {
        roundLock.run(UserScheduleLocks.COUNTER_RECONCILE, counterFlushService::reconcileRound);
    }
}

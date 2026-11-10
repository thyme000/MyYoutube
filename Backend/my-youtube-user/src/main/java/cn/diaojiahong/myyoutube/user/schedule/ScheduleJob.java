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
     * 每 30s 把粉丝数、关注数的缓冲落库。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void flushUserCounters() {
        roundLock.run("schedule:lock:user:counter:flush", counterFlushService::flushRound);
    }

    /**
     * 每小时用 COUNT(*) 覆盖一次粉丝数和关注数。
     */
    @Scheduled(cron = "0 40 * * * ?")
    public void reconcileUserCounters() {
        roundLock.run("schedule:lock:user:counter:reconcile", counterFlushService::reconcileRound);
    }
}

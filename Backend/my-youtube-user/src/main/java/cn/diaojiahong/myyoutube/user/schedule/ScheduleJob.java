package cn.diaojiahong.myyoutube.user.schedule;

import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * user 域定时任务: 粉丝数/关注数/获赞量的落库与对账。
 * 获赞量此前是手写的认领落库循环(漏扫 inflight、ES 同步压在 ack 前会放大重放),
 * 已收敛为 UserLikeCounterTarget 走标准骨架, 本类只剩调度入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleJob {

    private final CounterFlushService counterFlushService;
    private final ScheduleRoundLock roundLock;

    /**
     * 每 30s 把粉丝数/关注数/获赞量的缓冲落库。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void flushUserCounters() {
        roundLock.run("schedule:lock:user:counter:flush", counterFlushService::flushRound);
    }

    /**
     * 每小时用 COUNT(*) 覆盖一次(获赞量无权威来源, 由 CounterType.reconcile 自行跳过)。
     */
    @Scheduled(cron = "0 40 * * * ?")
    public void reconcileUserCounters() {
        roundLock.run("schedule:lock:user:counter:reconcile", counterFlushService::reconcileRound);
    }
}

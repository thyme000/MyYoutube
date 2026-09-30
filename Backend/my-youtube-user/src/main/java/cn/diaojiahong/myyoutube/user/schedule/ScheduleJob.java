package cn.diaojiahong.myyoutube.user.schedule;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterFlushService;
import cn.diaojiahong.myyoutube.common.counter.CounterKeys;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.search.api.UserDocPort;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * user 域定时任务: 粉丝数/关注数落库与对账, 获赞量落库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleJob {

    private final IUserService userService;
    private final UserDocPort userDocPort;
    private final CounterBuffer counterBuffer;
    private final CounterFlushService counterFlushService;
    private final ScheduleRoundLock roundLock;

    /**
     * 每 30s 把粉丝数/关注数的缓冲落库。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void flushUserCounters() {
        roundLock.run("schedule:lock:user:counter:flush", counterFlushService::flushRound);
    }

    /**
     * 每小时用 COUNT(*) 覆盖一次。
     */
    @Scheduled(cron = "0 40 * * * ?")
    public void reconcileUserCounters() {
        roundLock.run("schedule:lock:user:counter:reconcile", counterFlushService::reconcileRound);
    }

    /**
     * 每10分钟执行一次, 把 Redis 缓冲的获赞量刷进 MySQL 与 ES。
     * 该计数不做对账覆盖(权威值要跨表聚合), 所以只修认领的丢更新 bug, 周期保持原样。
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void updateUserLikeCount() {
        roundLock.run("schedule:lock:user:like-count", this::flushUserLikeCount);
    }

    private void flushUserLikeCount() {
        for (Long userId : counterBuffer.scanIds(CounterKeys.pendingPrefix(CounterType.USER_LIKE))) {
            Long delta = counterBuffer.claim(CounterType.USER_LIKE, userId);
            if (delta == null) {
                continue;
            }
            try {
                userService.lambdaUpdate()
                        .setIncrBy(User::getLikeCount, delta)
                        .eq(User::getId, userId)
                        .update();
                // 同步 ES 中用户获赞量
                userDocPort.adjustLikeCount(userId, delta);
                counterBuffer.ack(CounterType.USER_LIKE, userId);
            } catch (Exception e) {
                log.warn("用户获赞量落库失败, 下轮回补重放, userId={}, delta={}", userId, delta, e);
            }
        }
    }
}

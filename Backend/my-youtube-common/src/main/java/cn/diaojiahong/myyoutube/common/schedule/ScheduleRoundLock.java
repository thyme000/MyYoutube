package cn.diaojiahong.myyoutube.common.schedule;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 定时任务的整轮锁。拿不到锁必须立刻跳过、绝不阻塞：
 * {@code @Scheduled} 默认调度线程池 size=1，同模块所有定时任务共用一条线程，
 * 任何一个在这里等锁，会把同模块其它定时任务一起堵死。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRoundLock {

    private final ObjectProvider<RedissonClient> redissonClientProvider;

    /**
     * 持有锁执行一轮。用 ObjectProvider 取客户端：未接入 Redisson 的服务
     * （只依赖 common 而没有引 starter）退化为无锁执行，不影响启动。
     */
    public void run(String lockName, Runnable round) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            round.run();
            return;
        }
        RLock lock = redissonClient.getLock(lockName);
        try {
            // waitTime=0 -> 拿不到立刻返回 false
            if (!lock.tryLock(0, RedisConstants.SCHEDULE_LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                log.info("本轮 {} 已由其他实例执行, 跳过", lockName);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            round.run();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}

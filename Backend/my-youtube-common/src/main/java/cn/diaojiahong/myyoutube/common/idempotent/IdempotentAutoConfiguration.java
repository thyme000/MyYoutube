package cn.diaojiahong.myyoutube.common.idempotent;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 与 ChainAutoConfiguration 同理: 网关主类不落在根包扫不到 common 组件, 统一经自动装配暴露。
 * 网关侧无 @Idempotent 方法, 切面装配后空转无害。
 */
@AutoConfiguration
public class IdempotentAutoConfiguration {

    @Bean
    public IdempotentGuard idempotentGuard(StringRedisTemplate redis) {
        // 不注册为 TaskScheduler Bean，避免替代业务 @Scheduled 的默认调度器。
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("idempotent-lease-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return new IdempotentGuard(redis, scheduler);
    }

    @Bean
    public IdempotentAspect idempotentAspect(IdempotentGuard guard) {
        return new IdempotentAspect(guard);
    }
}

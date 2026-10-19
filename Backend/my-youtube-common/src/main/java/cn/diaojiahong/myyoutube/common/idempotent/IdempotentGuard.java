package cn.diaojiahong.myyoutube.common.idempotent;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/** 注解与批量任务共用占位；token 仅保护 Redis 状态，不能撤销已发出的外部请求。 */
@Slf4j
@RequiredArgsConstructor
public class IdempotentGuard implements AutoCloseable {
    public static final long DEFAULT_LEASE_SECONDS = 30;
    public static final String DONE = "DONE";
    private static final String RUNNING = "RUNNING:";
    private static final RedisScript<List> ACQUIRE = RedisScript.of(
            new ClassPathResource("lua/idempotent-acquire.lua"), List.class);
    private static final RedisScript<Long> RENEW = RedisScript.of(
            new ClassPathResource("lua/idempotent-renew.lua"), Long.class);
    private static final RedisScript<Long> FINISH = RedisScript.of(
            new ClassPathResource("lua/idempotent-finish.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final ThreadPoolTaskScheduler scheduler;
    private final ThreadLocal<Lease> current = new ThreadLocal<>();

    public Lease currentLease() {
        Lease lease = current.get();
        if (lease == null) throw new IllegalStateException("当前调用未经过幂等切面");
        return lease;
    }

    Lease enter(Lease lease) {
        Lease previous = current.get();
        current.set(lease);
        return previous;
    }

    void exit(Lease previous) {
        if (previous == null) current.remove();
        else current.set(previous);
    }

    @Override
    public void close() { scheduler.shutdown(); }

    public Lease acquire(String key, long leaseSeconds) {
        return acquireAll(List.of(key), leaseSeconds);
    }

    public Lease acquireAll(List<String> keys, long leaseSeconds) {
        if (keys == null || keys.isEmpty() || keys.stream().anyMatch(key -> !StringUtils.hasText(key)) || leaseSeconds <= 0) {
            throw new IllegalArgumentException("幂等 key 不能为空，执行租期必须为正数");
        }
        List<String> redisKeys = keys.stream().distinct().map(key -> RedisConstants.IDEMPOTENT + key).toList();
        String token = RUNNING + UUID.randomUUID();
        long millis = Math.multiplyExact(leaseSeconds, 1000);
        Lease lease = new Lease(redisKeys, token, null, millis);
        try {
            List<?> result = redis.execute(ACQUIRE, redisKeys, token, String.valueOf(millis));
            if (result == null || result.isEmpty()) throw new IllegalStateException("幂等占位未获确认");
            if (!"ACQUIRED".equals(result.get(0))) {
                String value = (String) result.get(1);
                if (value.startsWith(RUNNING) || redisKeys.size() > 1) {
                    throw new BizException(BizException.BIZ_ERROR, "操作正在处理中,请稍候");
                }
                return new Lease(redisKeys, null, value, millis);
            }
            lease.startRenewal();
            return lease;
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
    }

    public final class Lease implements AutoCloseable {
        private final List<String> keys;
        private final String token;
        private final String cachedValue;
        private final long millis;
        private ScheduledFuture<?> renewal;
        private boolean lost;
        private boolean finished;

        private Lease(List<String> keys, String token, String cachedValue, long millis) {
            this.keys = keys;
            this.token = token;
            this.cachedValue = cachedValue;
            this.millis = millis;
        }

        public boolean acquired() { return token != null; }
        public String cachedValue() { return cachedValue; }
        public String token() { return token; }

        private synchronized void startRenewal() {
            Duration interval = Duration.ofMillis(millis / 3);
            renewal = scheduler.scheduleWithFixedDelay(this::renew, Instant.now().plus(interval), interval);
            if (renewal == null) throw new IllegalStateException("幂等续期任务未启动");
        }

        public synchronized void checkActive() {
            if (!acquired() || finished || lost) throw new IllegalStateException("幂等执行占位已失效: " + keys);
        }

        public synchronized void assertOwned() {
            checkActive();
            renew();
            checkActive();
        }

        private synchronized void renew() {
            if (finished || lost) return;
            try {
                lost = !Long.valueOf(1).equals(redis.execute(RENEW, keys, token, String.valueOf(millis)));
            } catch (RuntimeException error) {
                lost = true;
                log.error("幂等占位续期失败: keys={}", keys, error);
            }
            if (lost) cancelRenewal();
        }

        public synchronized void complete(String value, long ttlSeconds) {
            if (ttlSeconds < 0) throw new IllegalArgumentException("幂等完成缓存期限不能为负数");
            checkActive();
            Long result = redis.execute(FINISH, keys, token, value,
                    String.valueOf(Math.multiplyExact(ttlSeconds, 1000)));
            if (!Long.valueOf(keys.size()).equals(result)) {
                lost = true;
                cancelRenewal();
                throw new IllegalStateException("幂等完成标记未获确认: " + keys);
            }
            finished = true;
            cancelRenewal();
        }

        @Override
        public synchronized void close() {
            if (!acquired() || finished) return;
            finished = true;
            cancelRenewal();
            try {
                redis.execute(FINISH, keys, token, DONE, "0");
            } catch (RuntimeException error) {
                log.error("幂等占位释放失败，等待租期过期: keys={}", keys, error);
            }
        }

        private void cancelRenewal() {
            if (renewal != null) renewal.cancel(false);
        }
    }
}

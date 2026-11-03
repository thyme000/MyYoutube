package cn.diaojiahong.myyoutube.content.cache;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 内容展示资源缓存，访问校验和签名响应由业务服务处理。
 * Redis 读取或取锁异常时直接回源，本次不填充缓存；填充失败不影响成功结果。
 * 正常锁竞争只等待配置的时间，超时且仍未命中则返回 503，避免并发绕锁回源。
 * loader 必须自行限制数据库或对象存储调用时间，锁等待时间不限制 loader 的执行时间。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentResourceCache implements ContentResourceReader {
    private static final String NULL_PAYLOAD = "null";
    private static final String LOAD_LOCK_PREFIX = "lock:";

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ContentResourceCacheProperties properties;
    private final MeterRegistry metrics;

    @Override
    public <T> T get(ContentResourceRegion region, String resourceId, Class<T> type, Supplier<T> loader) {
        String key = region.key(resourceId);
        CachedValue<T> cached = read(key, type);
        return get(region, key, type, loader, cached);
    }

    /**
     * 合并当前请求已知的 key；返回的 reader 仅供本次请求使用，不能跨请求保存。
     * 仅保存原始 JSON 字符串，使用时才解码；预取不构成访问校验，仍由调用方完成。
     * 未命中仍逐项加锁、重新读取，不能把批量读取结果用作锁内双检。
     * 当前部署为单节点 Redis；改用 Cluster 时需重新评估跨 slot 的批量读取。
     */
    public ContentResourceReader readTogether(String resourceId, ContentResourceRegion... regions) {
        List<String> keys = Arrays.stream(regions).map(region -> region.key(resourceId)).distinct().toList();
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException unavailable) {
            log.warn("Content resource cache batch read failed, loading from source keys={}", keys, unavailable);
            recordFailure(MetricNames.CONTENT_CACHE_BATCH_READ_FAILED);
            return new ContentResourceReader() {
                @Override
                public <T> T get(ContentResourceRegion region, String id, Class<T> type, Supplier<T> loader) {
                    return loader.get();
                }
            };
        }

        Map<String, String> payloads = new HashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            payloads.put(keys.get(index), Objects.requireNonNull(values).get(index));
        }

        return new ContentResourceReader() {
            @Override
            public <T> T get(ContentResourceRegion region, String id, Class<T> type, Supplier<T> loader) {
                String key = region.key(id);
                if (!payloads.containsKey(key)) {
                    return ContentResourceCache.this.get(region, id, type, loader);
                }

                CachedValue<T> cached = decode(key, payloads.get(key), type);
                return ContentResourceCache.this.get(region, key, type, loader, cached);
            }
        };
    }

    private <T> T get(ContentResourceRegion region, String key, Class<T> type, Supplier<T> loader,
                      CachedValue<T> cached) {
        if (cached.status() == ReadStatus.HIT) return cached.value();
        if (cached.status() == ReadStatus.UNAVAILABLE) return loader.get();

        RLock lock;
        boolean acquired;
        try {
            lock = redisson.getLock(LOAD_LOCK_PREFIX + key);
            // 不指定固定租期，由 Redisson watchdog 在回源期间续期。
            acquired = lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "等待内容资源缓存加载被中断", interrupted);
        } catch (RuntimeException unavailable) {
            log.warn("Content resource cache lock unavailable, loading from source key={}", key, unavailable);
            return loader.get();
        }

        // 获取锁超时, 尝试读取缓存, 缓存不存在直接返回异常, 避免长时间等待
        if (!acquired) {
            CachedValue<T> latest = read(key, type);
            if (latest.status() == ReadStatus.HIT) return latest.value();
            if (latest.status() == ReadStatus.UNAVAILABLE) return loader.get();
            throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容资源正在加载，请稍后重试", null);
        }

        try {
            CachedValue<T> latest = read(key, type);
            if (latest.status() == ReadStatus.HIT) return latest.value();
            if (latest.status() == ReadStatus.UNAVAILABLE) return loader.get();

            // 只有正常返回的 null 表示不存在，回源异常不能写成空值缓存。
            T value = loader.get();
            populate(key, region, value);
            return value;
        } finally {
            try {
                // unlock 自带所有权检查，不能删除其他持有者的锁。
                lock.unlock();
            } catch (RuntimeException unavailable) {
                log.warn("Content resource cache unlock failed key={}", key, unavailable);
            }
        }
    }

    /** 尽力删除，失败仅记录日志和指标；不阻止已经开始的 loader 回填旧值。 */
    public void evict(ContentResourceRegion region, String resourceId) {
        String key = region.key(resourceId);
        try {
            redis.delete(key);
        } catch (RuntimeException unavailable) {
            log.warn("Content resource cache eviction failed key={}", key, unavailable);
            recordFailure(MetricNames.CONTENT_CACHE_EVICT_FAILED);
        }
    }

    private void recordFailure(String metricName) {
        try {
            metrics.counter(metricName).increment();
        } catch (RuntimeException metricsUnavailable) {
            /* 指标故障不能改变缓存降级或失效结果。 */
        }
    }

    private <T> CachedValue<T> read(String key, Class<T> type) {
        String payload;
        try {
            payload = redis.opsForValue().get(key);
        } catch (RuntimeException unavailable) {
            log.warn("Content resource cache read failed, loading from source key={}", key, unavailable);
            return new CachedValue<>(ReadStatus.UNAVAILABLE, null);
        }
        return decode(key, payload, type);
    }

    private <T> CachedValue<T> decode(String key, String payload, Class<T> type) {
        if (payload == null) return new CachedValue<>(ReadStatus.MISS, null);
        if (NULL_PAYLOAD.equals(payload)) return new CachedValue<>(ReadStatus.HIT, null);

        try {
            T value = JsonUtil.fromJson(payload, type);
            ReadStatus status = value == null ? ReadStatus.MISS : ReadStatus.HIT;
            return new CachedValue<>(status, value);
        } catch (RuntimeException malformed) {
            // 持锁后重新加载并覆盖，避免在无锁读阶段误删并发写入的新值。
            log.warn("Malformed content resource cache entry key={}", key, malformed);
            return new CachedValue<>(ReadStatus.MISS, null);
        }
    }

    private void populate(String key, ContentResourceRegion region, Object value) {
        try {
            String payload = value == null ? NULL_PAYLOAD : JsonUtil.toJson(value);
            long ttlSeconds = ttlSeconds(region, value);
            redis.opsForValue().set(key, payload, randomizedTtl(ttlSeconds));
        } catch (RuntimeException unavailable) {
            log.warn("Content resource cache fill failed key={}", key, unavailable);
        }
    }

    private long ttlSeconds(ContentResourceRegion region, Object value) {
        if (value == null) return properties.getNegativeTtlSeconds();
        return switch (region.ttlGroup()) {
            case STATE -> properties.getStateTtlSeconds();
            case ARTIFACT -> properties.getArtifactTtlSeconds();
        };
    }

    private Duration randomizedTtl(long ttlSeconds) {
        long maximumMillis = Duration.ofSeconds(ttlSeconds).toMillis();
        long minimumMillis = maximumMillis - maximumMillis / 5;
        long ttlMillis = ThreadLocalRandom.current().nextLong(minimumMillis, maximumMillis + 1);
        return Duration.ofMillis(ttlMillis);
    }

    private enum ReadStatus { HIT, MISS, UNAVAILABLE }

    private record CachedValue<T>(ReadStatus status, T value) {}
}

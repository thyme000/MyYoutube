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
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 内容展示资源缓存，访问校验和签名响应由业务服务处理。
 * 普通读取在 Redis 读取或取锁异常时直接回源，本次不填充缓存；填充失败不影响成功结果。
 * 正常锁竞争只等待配置的时间，超时且仍未命中则返回 503，避免并发绕锁回源。
 * loader 必须自行限制数据库或对象存储调用时间，锁等待时间不限制 loader 的执行时间。
 * 两种产物 TTL 均使用锁与双检；普通读取按累计播放量选择策略，大 V 预热使用常规 TTL。
 * 预热不读取已有缓存；拿到锁就加载并覆盖，锁竞争或取锁异常时跳过。
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
    public <T> T get(ContentResourceRegion region, String resourceId, Class<T> type,
            Supplier<T> loader, FillPolicy policy) {
        String key = region.key(resourceId);
        CachedValue<T> cached = read(key, type);
        return get(region, key, type, loader, cached, policy);
    }

    /**
     * 不检查已有缓存；并发尝试取锁，成功项并行加载，再由当前线程汇总并 pipeline 覆盖写入。
     * 锁竞争或 Redis 取锁故障均不加载该资源；覆盖写入会刷新 TTL。
     * loaderExecutor 必须与运行本方法的线程池独立；子任务仅返回结果，不修改批次状态。
     * 已获取的锁持有到全部加载和写入结束；成功项写入、锁释放后才统一上报资源加载失败。
     * 取锁与解锁显式使用同一 ownerId，各阶段发出全部请求后统一等待；普通读取仍使用同步锁 API。
     * 多项写入不保证原子性；后续产物加载失败时，仍尽力写入此前成功加载的产物。
     */
    public void warmupTogether(String resourceId, Executor loaderExecutor,
            WarmupResource<?>... resources) {
        List<String> keys = Arrays.stream(resources).map(resource -> resource.region().key(resourceId)).distinct().toList();
        var batch = new WarmupBatch();
        try {
            batch.acquireLocks(keys);
            batch.loadResources(resourceId, loaderExecutor, resources);
        } finally {
            try {
                batch.flush();
            } finally {
                batch.releaseLocks();
            }
        }
        batch.throwIfFailed(resourceId, resources);
    }

    /**
     * 合并当前请求已知的 key；返回的 reader 仅供本次请求使用，不能跨请求保存。
     * 仅保存原始 JSON 字符串，使用时才解码；预取不构成访问校验，仍由调用方完成。
     * 未命中时逐项加锁、重新读取，不能把批量读取结果用作锁内双检。
     * 当前部署为单节点 Redis；改用 Cluster 时需重新评估跨 slot 的批量读取。
     */
    public ContentResourceReader readTogether(String resourceId, ContentResourceRegion... regions) {
        List<String> keys = Arrays.stream(regions).map(region -> region.key(resourceId)).distinct().toList();
        Map<String, String> payloads = readPayloads(keys);
        if (payloads == null) {
            return new ContentResourceReader() {
                @Override
                public <T> T get(ContentResourceRegion region, String id, Class<T> type,
                        Supplier<T> loader, FillPolicy policy) {
                    return loadFromSource(region, policy, loader);
                }
            };
        }

        return new ContentResourceReader() {
            @Override
            public <T> T get(ContentResourceRegion region, String id, Class<T> type,
                    Supplier<T> loader, FillPolicy policy) {
                String key = region.key(id);
                if (!payloads.containsKey(key)) {
                    return ContentResourceCache.this.get(region, id, type, loader, policy);
                }

                CachedValue<T> cached = decode(key, payloads.get(key), type);
                return ContentResourceCache.this.get(region, key, type, loader, cached, policy);
            }
        };
    }

    private Map<String, String> readPayloads(List<String> keys) {
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException unavailable) {
            log.warn("Content resource cache batch read failed, loading from source keys={}", keys, unavailable);
            recordFailure(MetricNames.CONTENT_CACHE_BATCH_READ_FAILED);
            return null;
        }

        Map<String, String> payloads = new HashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            payloads.put(keys.get(index), Objects.requireNonNull(values).get(index));
        }
        return payloads;
    }

    private <T> T get(ContentResourceRegion region, String key, Class<T> type, Supplier<T> loader,
                      CachedValue<T> cached, FillPolicy policy) {
        if (cached.status() == ReadStatus.HIT) return cached.value();
        if (cached.status() == ReadStatus.UNAVAILABLE) return loadFromSource(region, policy, loader);

        RLock lock;
        try {
            lock = acquireLoadLock(key, properties.getLockWaitMillis());
        } catch (ContentResourceBusyException busy) {
            // 普通请求等待结束后再读一次，仍未命中才返回 503。
            CachedValue<T> latest = read(key, type);
            if (latest.status() == ReadStatus.HIT) return latest.value();
            if (latest.status() == ReadStatus.UNAVAILABLE) return loadFromSource(region, policy, loader);
            throw busy;
        }
        if (lock == null) {
            return loadFromSource(region, policy, loader);
        }

        try {
            CachedValue<T> latest = read(key, type);
            if (latest.status() == ReadStatus.HIT) return latest.value();
            if (latest.status() == ReadStatus.UNAVAILABLE) return loadFromSource(region, policy, loader);

            // 只有正常返回的 null 表示不存在，回源异常不能写成空值缓存。
            T value = loadFromSource(region, policy, loader);
            populate(key, region, value, policy);
            return value;
        } finally {
            unlock(lock);
        }
    }

    /** Redis 故障返回 null 供调用方直接回源；正常锁竞争与线程中断分别抛出异常。 */
    private RLock acquireLoadLock(String key, long waitMillis) {
        RLock lock;
        boolean acquired;
        try {
            lock = redisson.getLock(LOAD_LOCK_PREFIX + key);
            // 不指定固定租期，由 Redisson watchdog 在回源期间续期。
            acquired = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE, "等待内容资源缓存加载被中断", interrupted);
        } catch (RuntimeException unavailable) {
            log.warn("Content resource cache lock unavailable, loading from source key={}", key, unavailable);
            return null;
        }

        if (!acquired) {
            throw new ContentResourceBusyException();
        }
        return lock;
    }

    private void unlock(RLock lock) {
        try {
            // unlock 自带所有权检查，不能删除其他持有者的锁。
            lock.unlock();
        } catch (RuntimeException unavailable) {
            log.warn("Content resource cache unlock failed lock={}", lock.getName(), unavailable);
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

    private <T> T loadFromSource(ContentResourceRegion region, FillPolicy policy, Supplier<T> loader) {
        recordCounter(MetricNames.CONTENT_CACHE_SOURCE_LOADS, region, policy);
        try {
            return loader.get();
        } catch (RuntimeException failure) {
            recordCounter(MetricNames.CONTENT_CACHE_SOURCE_LOAD_FAILED, region, policy);
            throw failure;
        }
    }

    private void recordCounter(String name, ContentResourceRegion region, FillPolicy policy) {
        try {
            metrics.counter(name, "region", region.name(), "policy", policy.name()).increment();
        } catch (RuntimeException metricsUnavailable) {
            /* 指标故障不能影响缓存和回源。 */
        }
    }

    private void populate(String key, ContentResourceRegion region, Object value, FillPolicy policy) {
        try {
            String payload = value == null ? NULL_PAYLOAD : JsonUtil.toJson(value);
            long ttlSeconds = ttlSeconds(region, value, policy);
            redis.opsForValue().set(key, payload, randomizedTtl(ttlSeconds));
            recordCounter(MetricNames.CONTENT_CACHE_FILLS, region, policy);
        } catch (RuntimeException unavailable) {
            log.warn("Content resource cache fill failed key={}", key, unavailable);
            recordCounter(MetricNames.CONTENT_CACHE_FILL_FAILED, region, policy);
        }
    }

    private long ttlSeconds(ContentResourceRegion region, Object value, FillPolicy policy) {
        if (value == null) return properties.getNegativeTtlSeconds();
        return switch (region.ttlGroup()) {
            case STATE -> properties.getStateTtlSeconds();
            case ARTIFACT -> policy == FillPolicy.SHORT_TTL
                    ? properties.getShortArtifactTtlSeconds() : properties.getArtifactTtlSeconds();
        };
    }

    private Duration randomizedTtl(long ttlSeconds) {
        long maximumMillis = Duration.ofSeconds(ttlSeconds).toMillis();
        long minimumMillis = maximumMillis - maximumMillis / 5;
        long ttlMillis = ThreadLocalRandom.current().nextLong(minimumMillis, maximumMillis + 1);
        return Duration.ofMillis(ttlMillis);
    }

    private class WarmupBatch {
        private final long ownerId = Thread.currentThread().getId();
        private final Map<String, CompletableFuture<RLock>> acquisitions = new LinkedHashMap<>();
        private final Map<String, CompletableFuture<?>> results = new LinkedHashMap<>();
        private final List<PendingWrite> writes = new ArrayList<>();

        private void acquireLocks(List<String> keys) {
            for (String key : keys) {
                acquisitions.put(key, acquireAsync(key));
            }
            awaitAll(acquisitions.values());
            checkInterrupted();
        }

        private CompletableFuture<RLock> acquireAsync(String key) {
            try {
                RLock lock = redisson.getLock(LOAD_LOCK_PREFIX + key);
                // leaseTime=-1 保留 watchdog；所有异步请求固定使用预热线程的所有者 ID。
                return lock.tryLockAsync(0, -1, TimeUnit.MILLISECONDS, ownerId).toCompletableFuture()
                        .handle((acquired, failure) -> {
                            if (failure != null) {
                                throw new ContentResourceLockUnavailableException(failure);
                            }
                            if (!acquired) {
                                throw new ContentResourceBusyException();
                            }
                            return lock;
                        });
            } catch (RuntimeException unavailable) {
                return CompletableFuture.failedFuture(new ContentResourceLockUnavailableException(unavailable));
            }
        }

        private void awaitLock(String key) {
            try {
                acquisitions.get(key).join();
            } catch (CompletionException failure) {
                checkInterrupted();
                if (failure.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw failure;
            }
            checkInterrupted();
        }

        private void awaitAll(Collection<? extends CompletableFuture<?>> futures) {
            CompletableFuture<?>[] pending = futures.toArray(CompletableFuture[]::new);
            // 不取消仍在执行的取锁或回源；等全部结果明确后再清理成功取得的锁。
            CompletableFuture.allOf(pending).handle((ignored, failure) -> null).join();
        }

        private void checkInterrupted() {
            if (Thread.currentThread().isInterrupted()) {
                throw new DependencyException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "等待内容资源预热被中断", new InterruptedException());
            }
        }

        private void releaseLocks() {
            awaitAll(results.values());
            awaitAll(acquisitions.values());
            List<CompletableFuture<Void>> releases = new ArrayList<>();
            for (CompletableFuture<RLock> acquisition : acquisitions.values()) {
                if (acquisition.isCompletedExceptionally()) {
                    continue;
                }
                RLock lock = acquisition.getNow(null);
                try {
                    CompletableFuture<Void> release = lock.unlockAsync(ownerId).toCompletableFuture()
                            .handle((ignored, failure) -> {
                                if (failure != null) {
                                    log.warn("Content resource cache unlock failed lock={}", lock.getName(), failure);
                                }
                                return null;
                            });
                    releases.add(release);
                } catch (RuntimeException unavailable) {
                    log.warn("Content resource cache unlock failed lock={}", lock.getName(), unavailable);
                }
            }
            awaitAll(releases);
        }

        private void loadResources(String id, Executor executor, WarmupResource<?>[] resources) {
            for (WarmupResource<?> resource : resources) {
                String key = resource.region().key(id);
                try {
                    awaitLock(key);
                    results.put(key, loadAsync(resource, executor));
                } catch (RuntimeException failure) {
                    results.put(key, CompletableFuture.failedFuture(failure));
                }
            }
            awaitAll(results.values());
            checkInterrupted();

            for (WarmupResource<?> resource : resources) {
                String key = resource.region().key(id);
                CompletableFuture<?> result = results.get(key);
                if (!result.isCompletedExceptionally()) {
                    stage(key, resource.region(), result.getNow(null), resource.policy());
                }
            }
        }

        private <T> CompletableFuture<T> loadAsync(WarmupResource<T> resource, Executor executor) {
            return CompletableFuture.supplyAsync(
                    () -> loadFromSource(resource.region(), resource.policy(), resource.loader()), executor);
        }

        private void throwIfFailed(String id, WarmupResource<?>[] resources) {
            ContentResourceWarmupException firstFailure = null;
            for (WarmupResource<?> resource : resources) {
                CompletableFuture<?> result = results.get(resource.region().key(id));
                if (result.isCompletedExceptionally()) {
                    try {
                        result.join();
                    } catch (CompletionException failure) {
                        var resourceFailure = new ContentResourceWarmupException(resource.region(), failure.getCause());
                        if (firstFailure == null) {
                            firstFailure = resourceFailure;
                        } else {
                            firstFailure.addSuppressed(resourceFailure);
                        }
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }

        private void stage(String key, ContentResourceRegion region, Object value, FillPolicy policy) {
            try {
                String payload = value == null ? NULL_PAYLOAD : JsonUtil.toJson(value);
                Duration ttl = randomizedTtl(ttlSeconds(region, value, policy));
                writes.add(new PendingWrite(key, region, policy, payload, ttl));
            } catch (RuntimeException failure) {
                log.warn("Content resource cache fill failed key={}", key, failure);
                recordCounter(MetricNames.CONTENT_CACHE_FILL_FAILED, region, policy);
            }
        }

        private void flush() {
            if (writes.isEmpty()) {
                return;
            }

            try {
                redis.executePipelined((RedisCallback<Object>) connection -> {
                    for (PendingWrite write : writes) {
                        connection.stringCommands().set(Objects.requireNonNull(StringRedisSerializer.UTF_8.serialize(write.key())),
                            Objects.requireNonNull(StringRedisSerializer.UTF_8.serialize(write.payload())),
                                Expiration.from(write.ttl()), SetOption.UPSERT);
                    }
                    return null;
                });
                writes.forEach(write -> recordCounter(MetricNames.CONTENT_CACHE_FILLS, write.region(), write.policy()));
            } catch (RuntimeException failure) {
                log.warn("Content resource cache warmup pipeline failed keys={}",
                        writes.stream().map(PendingWrite::key).toList(), failure);
                // pipeline 可能部分成功；失败时不能确认的写入均计为失败，不重试。
                writes.forEach(write -> recordCounter(MetricNames.CONTENT_CACHE_FILL_FAILED, write.region(), write.policy()));
            }
        }
    }

    private record PendingWrite(String key, ContentResourceRegion region, FillPolicy policy, String payload, Duration ttl) {}

    private enum ReadStatus { HIT, MISS, UNAVAILABLE }

    private record CachedValue<T>(ReadStatus status, T value) {}
}

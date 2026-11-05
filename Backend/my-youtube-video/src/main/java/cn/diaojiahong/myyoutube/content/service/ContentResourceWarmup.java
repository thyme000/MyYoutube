package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceBusyException;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCacheConfiguration;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceLockUnavailableException;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader.FillPolicy;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceRegion;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceWarmupException;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceWarmupProperties;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 发布提交后尽力异步预热 CONTENT_MEDIA、UNDERSTANDING 元数据；不预热清单、字幕正文和访问状态。
 * 大 V 发布预热不受累计播放量门槛限制，使用 STANDARD 覆盖产物并刷新 TTL，不签发用户票据。
 * 已有请求加载时直接跳过；排队满或进程退出丢失的任务由正常读取补缓存。
 */
@Slf4j
@Service
public class ContentResourceWarmup {
    private final Executor executor;
    private final Executor loaderExecutor;
    private final ContentResourceWarmupProperties properties;
    private final ContentResourceWarmupLoader loader;
    private final ContentMediaResources media;
    private final ContentUnderstandingResources understanding;
    private final ContentResourceCache cache;
    private final MeterRegistry metrics;

    public ContentResourceWarmup(
            @Qualifier(ContentResourceCacheConfiguration.WARMUP_EXECUTOR) Executor executor,
            @Qualifier(ContentResourceCacheConfiguration.WARMUP_LOADER_EXECUTOR) Executor loaderExecutor,
            ContentResourceWarmupProperties properties, ContentResourceWarmupLoader loader,
            ContentMediaResources media, ContentUnderstandingResources understanding,
            ContentResourceCache cache, MeterRegistry metrics) {
        this.executor = executor;
        this.loaderExecutor = loaderExecutor;
        this.properties = properties;
        this.loader = loader;
        this.media = media;
        this.understanding = understanding;
        this.cache = cache;
        this.metrics = metrics;
    }

    /** 仅供发布事务的 afterCommit 调用；预热任务提交失败不影响发布结果。 */
    public void submit(Long id) {
        if (!properties.isEnabled()) {
            skipped(SkipReason.DISABLED);
            return;
        }

        try {
            executor.execute(() -> warm(id));
            record(MetricNames.CONTENT_CACHE_WARMUP_SUBMITTED);
        } catch (RejectedExecutionException rejected) {
            skipped(SkipReason.EXECUTOR_REJECTED);
            log.warn("Content resource warmup rejected contentId={}", id);
        } catch (RuntimeException failure) {
            record(MetricNames.CONTENT_CACHE_WARMUP_FAILED);
            log.warn("Content resource warmup submission failed contentId={}", id, failure);
        }
    }

    private void warm(Long id) {
        try {
            Content content = eligibleContent(id);
            if (content == null) {
                return;
            }

            cache.warmupTogether(id.toString(), loaderExecutor,
                    media.warmupResource(content, FillPolicy.STANDARD), understanding.warmupResource(id, FillPolicy.STANDARD));
            // null 仍按负缓存处理，不重复校验资源完整性；实际写入结果由缓存填充指标区分。
            record(MetricNames.CONTENT_CACHE_WARMUP_READ_COMPLETED);
        } catch (ContentResourceWarmupException failure) {
            failedResource(id, failure);
        } catch (RejectedExecutionException rejected) {
            skipped(SkipReason.EXECUTOR_REJECTED);
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) {
                skipped(SkipReason.INTERRUPTED);
                return;
            }
            failed(id, failure);
        }
    }

    private Content eligibleContent(Long id) {
        try {
            Content content = loader.loadIfEligible(id);
            if (content == null) {
                skipped(SkipReason.BELOW_FAN_THRESHOLD);
            }
            return content;
        } catch (BizException rejected) {
            if (rejected.getErrorCode() == ErrorCode.NOT_FOUND) {
                skipped(SkipReason.NOT_READABLE);
                return null;
            }
            throw rejected;
        }
    }

    private void failedResource(Long id, ContentResourceWarmupException failure) {
        SkipReason reason = skipReason(failure);
        if (reason != null) {
            for (Throwable other : failure.getSuppressed()) {
                if (other instanceof ContentResourceWarmupException resourceFailure && skipReason(resourceFailure) == null) {
                    reason = null;
                    break;
                }
            }
        }
        if (reason == null) {
            failed(id, failure);
        } else {
            skipped(reason);
        }
    }

    private SkipReason skipReason(ContentResourceWarmupException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof ContentResourceBusyException) {
            return SkipReason.LOCKED;
        }
        if (cause instanceof ContentResourceLockUnavailableException) {
            return SkipReason.REDIS_UNAVAILABLE;
        }
        if (cause instanceof RejectedExecutionException) {
            return SkipReason.EXECUTOR_REJECTED;
        }
        if (failure.getRegion() == ContentResourceRegion.CONTENT_MEDIA
                && cause instanceof BizException rejected && rejected.getErrorCode() == ErrorCode.STATE_CONFLICT) {
            return SkipReason.NOT_READY;
        }
        return null;
    }

    private void failed(Long id, RuntimeException failure) {
        record(MetricNames.CONTENT_CACHE_WARMUP_FAILED);
        log.warn("Content resource warmup failed contentId={}", id, failure);
    }

    private void skipped(SkipReason reason) {
        record(MetricNames.CONTENT_CACHE_WARMUP_SKIPPED, "reason", reason.name());
    }

    private void record(String name, String... tags) {
        try {
            metrics.counter(name, tags).increment();
        } catch (RuntimeException metricsUnavailable) {
            /* 预热及指标均不能影响已提交的发布。 */
        }
    }

    private enum SkipReason {
        DISABLED,
        EXECUTOR_REJECTED,
        BELOW_FAN_THRESHOLD,
        NOT_READABLE,
        NOT_READY,
        LOCKED,
        REDIS_UNAVAILABLE,
        INTERRUPTED
    }
}

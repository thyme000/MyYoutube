package cn.diaojiahong.myyoutube.common.concurrent;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;

@Aspect
@Component
public class AsyncTimeoutAspect {
    private final AsyncTimeoutProperties properties;
    private final BeanFactory beans;
    private final MeterRegistry metrics;
    private final TaskScheduler timer;

    public AsyncTimeoutAspect(AsyncTimeoutProperties properties, BeanFactory beans, MeterRegistry metrics,
                              @Qualifier("asyncTimeoutScheduler") TaskScheduler timer) {
        this.properties = properties;
        this.beans = beans;
        this.metrics = metrics;
        this.timer = timer;
    }

    @Around("@annotation(asyncTimeout)")
    public Object invoke(ProceedingJoinPoint method, AsyncTimeout asyncTimeout) {
        if (((MethodSignature) method.getSignature()).getReturnType() != CompletableFuture.class)
            throw new IllegalStateException("@AsyncTimeout 方法必须返回 CompletableFuture");

        AsyncTimeoutProperties.Policy policy = properties.getResources().get(asyncTimeout.value());
        if (policy == null) throw new IllegalStateException("未配置异步超时资源: " + asyncTimeout.value());
        Executor executor = beans.getBean(policy.getExecutor(), Executor.class);
        CompletableFuture<Object> response = new CompletableFuture<>();

        // 超时只完成对外结果，不中断已运行任务；底层调用仍由客户端固定超时兜住。
        ScheduledFuture<?> timeout = timer.schedule(() -> {
            if (response.completeExceptionally(new TimeoutException("等待超时: " + asyncTimeout.value())))
                metrics.counter(MetricNames.ASYNC_TIMEOUTS, "resource", asyncTimeout.value()).increment();
        }, Instant.now().plusMillis(policy.getTimeoutMillis()));
        response.whenComplete((value, error) -> timeout.cancel(false));

        try {
            executor.execute(() -> {
                if (response.isDone()) return;

                try {
                    CompletableFuture<?> actual = (CompletableFuture<?>) method.proceed();
                    actual.whenComplete((value, error) -> {
                        if (error == null) response.complete(value);
                        else response.completeExceptionally(error);
                    });
                } catch (Throwable error) {
                    response.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException rejected) {
            metrics.counter(MetricNames.ASYNC_REJECTED, "resource", asyncTimeout.value()).increment();
            response.completeExceptionally(rejected);
        }

        return response;
    }
}

package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.concurrent.AsyncTimeoutProperties;
import cn.diaojiahong.myyoutube.common.concurrent.AsyncTimeoutAspect;
import cn.diaojiahong.myyoutube.common.concurrent.AsyncTimeoutConfiguration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import cn.diaojiahong.myyoutube.config.RecallConfig;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

final class RecallTestSupport implements AutoCloseable {
    final ThreadPoolTaskExecutor keywordExecutor = new RecallConfig().recallExecutor();
    final ThreadPoolTaskExecutor vectorExecutor;
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final AsyncTimeoutAspect aspect;
    final ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) new AsyncTimeoutConfiguration().asyncTimeoutScheduler();

    RecallTestSupport(int waitMillis) {
        scheduler.initialize();
        VectorProperties vectors = new VectorProperties();
        vectors.setQueryMaxConcurrent(1); vectors.setQueryQueueCapacity(1);
        vectorExecutor = new RecallConfig().queryVectorExecutor(vectors);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("recallExecutor", keywordExecutor);
        beans.registerSingleton("queryVectorExecutor", vectorExecutor);
        AsyncTimeoutProperties limits = new AsyncTimeoutProperties();
        for (String resource : new String[]{"keyword-recall", "vector-recall"}) {
            AsyncTimeoutProperties.Policy policy = new AsyncTimeoutProperties.Policy();
            policy.setTimeoutMillis(waitMillis);
            policy.setExecutor(resource.equals("keyword-recall") ? "recallExecutor" : "queryVectorExecutor");
            limits.getResources().put(resource, policy);
        }
        aspect = new AsyncTimeoutAspect(limits, beans, metrics, scheduler);
    }

    <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Override public void close() {
        keywordExecutor.shutdown(); vectorExecutor.shutdown(); scheduler.shutdown(); metrics.close();
    }
}

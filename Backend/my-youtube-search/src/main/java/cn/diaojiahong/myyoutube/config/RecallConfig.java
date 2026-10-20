package cn.diaojiahong.myyoutube.config;

import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class RecallConfig {

    /** 关键词独立执行，向量阻塞不占用该池。 */
    @Bean("recallExecutor")
    public ThreadPoolTaskExecutor recallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("recall-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
    @Bean("queryVectorExecutor")
    public ThreadPoolTaskExecutor queryVectorExecutor(
            VectorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("query-vector-");
        executor.setCorePoolSize(properties.getQueryMaxConcurrent());
        executor.setMaxPoolSize(properties.getQueryMaxConcurrent());
        executor.setQueueCapacity(properties.getQueryQueueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

}

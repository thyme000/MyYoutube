package cn.diaojiahong.myyoutube.search.hot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class HotSearchConfiguration {
    @Bean
    public Clock hotSearchClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ThreadPoolTaskExecutor hotSearchCacheExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("hot-search-cache-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}

package cn.diaojiahong.myyoutube.search.vector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class VectorConfig {

    /**
     * 向量补算线程池: 排队满直接丢弃——文档先无向量入库不影响关键词搜索,
     * 被丢弃的由 rebuild 接口兜底补算, 不能反过来拖垮 MQ 消费
     */
    @Bean("vectorEmbedExecutor")
    public ThreadPoolTaskExecutor vectorEmbedExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("vector-embed-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setRejectedExecutionHandler(new LogAndDiscardPolicy());
        executor.initialize();
        return executor;
    }

    @Slf4j
    static class LogAndDiscardPolicy extends ThreadPoolExecutor.DiscardPolicy {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            log.warn("向量补算队列已满, 任务丢弃, 等待 rebuild 兜底");
        }
    }
}

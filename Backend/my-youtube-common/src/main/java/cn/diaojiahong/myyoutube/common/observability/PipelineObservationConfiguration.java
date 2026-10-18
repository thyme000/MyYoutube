package cn.diaojiahong.myyoutube.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;

public class PipelineObservationConfiguration {
    @Bean public PipelineEventRecorder pipelineEventRecorder(JdbcTemplate jdbc, PipelineObservationProperties properties, TransactionTemplate transaction) {
        if (!(transaction.getTransactionManager() instanceof DataSourceTransactionManager manager))
            throw new IllegalStateException("Pipeline outbox requires a local JDBC transaction manager");
        return new PipelineEventRecorder(jdbc, properties, manager);
    }
    @Bean public PipelineEvents pipelineEvents(PipelineObservationProperties properties, PipelineEventRecorder recorder, Environment environment) {
        return new PipelineEvents(properties, recorder, environment.getRequiredProperty("spring.application.name"), Clock.systemUTC());
    }
    @Bean public OutboxStore pipelineOutboxStore(JdbcTemplate jdbc, TransactionTemplate transaction, PipelineObservationProperties properties) {
        return new OutboxStore(jdbc, transaction, properties, Clock.systemUTC());
    }
    @Bean public OutboxMetrics pipelineOutboxMetrics(JdbcTemplate jdbc, PipelineObservationProperties properties, MeterRegistry registry) {
        return new OutboxMetrics(jdbc, properties, Clock.systemUTC(), registry);
    }
    @Bean public OutboxJobs pipelineOutboxJobs(OutboxStore store, RocketMQTemplate mq, PipelineObservationProperties properties, MeterRegistry metrics) {
        return new OutboxJobs(store, new RocketMqOutboxPublisher(mq), properties, metrics);
    }
}

package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.observability.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObservationWiringTest {
    @Test void importedConfigurationWiresLocalTransactionsAndDisabledJobsNeedNoOutboxTable() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("spring.application.name", "transcode-test")));
            var ds = new DriverManagerDataSource("jdbc:h2:mem:observation-wiring;MODE=MySQL", "sa", "");
            var mq = mock(RocketMQTemplate.class);
            context.registerBean(DataSource.class, () -> ds);
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(ds));
            context.registerBean(TransactionTemplate.class, () -> new TransactionTemplate(new DataSourceTransactionManager(ds)));
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(RocketMQTemplate.class, () -> mq);
            context.registerBean(PipelineObservationProperties.class, PipelineObservationProperties::new);
            context.register(PipelineObservationConfiguration.class); context.refresh();
            clearInvocations(mq);
            assertFalse(context.getBean(PipelineEvents.class).enabled());
            var jobs = context.getBean(OutboxJobs.class); jobs.deliver(); jobs.cleanup();
            verifyNoInteractions(mq);
        }
    }
}

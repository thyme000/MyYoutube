package cn.diaojiahong.myyoutube.common.monitoring;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.annotation.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BusinessMetricsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Operations proxy;

    @BeforeEach
    void proxy() {
        var factory = new AspectJProxyFactory(new Operations());
        factory.addAspect(new BusinessMetrics(registry));
        proxy = factory.getProxy();
    }

    @Test
    void emptyIsSuccessfulResultNotInfrastructureFailure() {
        proxy.search();
        assertEquals(1, count("search_content", "empty"));
        assertEquals(0, count("search_content", "failure"));
    }

    @Test
    void emptyCursorPagesUseTheSameEmptyOutcome() {
        proxy.cursorSearch(List.of());
        proxy.cursorSearch(List.of("content"));
        assertEquals(1, count("search_content", "empty"));
        assertEquals(1, count("search_content", "success"));
    }

    @Test
    void exceptionsKeepIdentityAndNeverBecomeSuccess() {
        var error = new BizException(ErrorCode.INVALID_ARGUMENT, "private input");
        assertSame(error, assertThrows(BizException.class, () -> proxy.login(error)));
        assertEquals(1, count("login", "business_error"));
        assertEquals(0, count("login", "success"));
        assertEquals(0, registry.get(MetricNames.BUSINESS_INFLIGHT).tag("operation", "login").gauge().value());
        assertSame(error, assertThrows(BizException.class, () -> proxy.login(error)));
        assertEquals(2, count("login", "business_error"));
    }

    @Test
    void infrastructureFailureAndUnchangedResultAreSeparate() {
        var error = new IllegalStateException("database down");
        assertSame(error, assertThrows(IllegalStateException.class, () -> proxy.login(error)));
        assertEquals(1, count("login", "failure"));

        var dependency = new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "服务不可用", error);
        assertSame(dependency, assertThrows(DependencyException.class, () -> proxy.login(dependency)));
        assertEquals(2, count("login", "failure"));
        assertEquals(0, count("login", "business_error"));

        proxy.review();
        assertEquals(1, count("manual_review", "no_change"));
    }

    @Test
    void joinedTransactionDefersSuccessAndRecordsRollback() {
        var manager = new TestTransactionManager();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            proxy.search();
            assertEquals(0, count("search_content", "empty"));
            assertEquals(1, registry.get(MetricNames.BUSINESS_INFLIGHT).gauge().value());
            tx.setRollbackOnly();
        });
        assertEquals(1, count("search_content", "failure"));
        assertEquals(0, registry.get(MetricNames.BUSINESS_INFLIGHT).gauge().value());
    }

    @Test
    void joinedTransactionRecordsSuccessOnlyAfterCommit() {
        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(tx -> proxy.search());
        assertEquals(1, count("search_content", "empty"));
        assertEquals(1, registry.get(MetricNames.BUSINESS_DURATION).timer().count());
    }

    @Test
    void springAdvisorOrderIncludesCommitFailure() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            BusinessMetricsTest.TestTransactionManager manager = context.getBean(TestTransactionManager.class);
            manager.failCommit = true;
            assertThrows(IllegalStateException.class, () -> context.getBean(Operations.class).committed());

            SimpleMeterRegistry meters = context.getBean(SimpleMeterRegistry.class);
            assertEquals(1, meters.get(MetricNames.BUSINESS_OPERATIONS)
                    .tag("operation", "register")
                    .tag("outcome", "failure")
                    .counter()
                    .count());
            assertEquals(0, meters.get(MetricNames.BUSINESS_OPERATIONS)
                    .tag("operation", "register")
                    .tag("outcome", "success")
                    .counter()
                    .count());
        }
    }

    @Test
    void labelsNeverContainArgumentsOrExceptionMessages() {
        proxy.login(null);
        registry.getMeters().forEach(m -> {
            assertTrue(m.getId()
                    .getTags()
                    .stream()
                    .allMatch(t -> List.of("chain", "operation", "outcome").contains(t.getKey())));
            assertFalse(m.getId().toString().contains("private"));
        });
    }

    @Test
    void startupExposesObservedOperationsBeforeAnyTraffic() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            SimpleMeterRegistry meters = context.getBean(SimpleMeterRegistry.class);
            assertEquals(
                    1,
                    meters.get(MetricNames.BUSINESS_OPERATION_ENABLED).tag("operation", "register").gauge().value());
            assertEquals(0, meters.get(MetricNames.BUSINESS_OPERATIONS)
                    .tag("operation", "register")
                    .tag("outcome", "success")
                    .counter()
                    .count());
            assertNull(meters.find(MetricNames.BUSINESS_OPERATION_ENABLED).tag("operation", "send_message").gauge());
        }
    }

    double count(String operation, String outcome) {
        return registry.get(MetricNames.BUSINESS_OPERATIONS)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    public static class Operations {
        @ObservedBusiness(BusinessOperation.SEARCH_CONTENT)
        public PageResult<String> search() {
            return PageResult.of(0, 0, List.of());
        }

        @ObservedBusiness(BusinessOperation.SEARCH_CONTENT)
        public CursorPage<String, String> cursorSearch(List<String> items) {
            return new CursorPage<>(items, null);
        }

        @ObservedBusiness(BusinessOperation.LOGIN)
        public void login(RuntimeException error) {
            if (error != null) {
                throw error;
            }
        }

        @ObservedBusiness(BusinessOperation.MANUAL_REVIEW)
        public boolean review() {
            return false;
        }

        @Transactional
        @ObservedBusiness(BusinessOperation.REGISTER)
        public void committed() {
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    @EnableTransactionManagement
    static class Config {
        @Bean
        SimpleMeterRegistry registry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        BusinessMetrics metrics(SimpleMeterRegistry registry) {
            return new BusinessMetrics(registry);
        }

        @Bean
        Operations operations() {
            return new Operations();
        }

        @Bean
        TestTransactionManager manager() {
            return new TestTransactionManager();
        }

        @Bean
        static BusinessMetricsCatalog catalog(org.springframework.beans.factory.ObjectProvider<BusinessMetrics> metrics) {
            return new BusinessMetricsCatalog(metrics);
        }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new IllegalStateException("commit failed");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}

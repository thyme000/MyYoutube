package cn.diaojiahong.myyoutube.common.resilience;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import com.alibaba.csp.sentinel.*;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.*;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.EventObserverRegistry;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Supplier;

@Slf4j
@Component
public class ModelCallGuard {
    private final MeterRegistry metrics;
    private final Environment environment;
    private NacosDataSource<List<DegradeRule>> source;
    public ModelCallGuard(ObjectProvider<MeterRegistry> metrics, Environment environment) {
        this.metrics = metrics.getIfAvailable(); this.environment = environment;
    }
    @PostConstruct public void initialize() {
        var rules = new ArrayList<DegradeRule>();
        // Sentinel exception-count rules open when failures strictly exceed count.
        for (String resource : List.of("ASR", "OCR", "LLM")) rules.add(new DegradeRule(resource)
                .setGrade(2).setCount(4).setTimeWindow(30).setStatIntervalMs(60000).setMinRequestAmount(5));
        DegradeRuleManager.loadRules(rules);
        EventObserverRegistry.getInstance().addStateChangeObserver("model-alerts", (previous, current, rule, snapshot) -> {
            log.warn("Model circuit transition resource={} from={} to={}", rule.getResource(), previous, current);
            if (metrics != null) metrics.counter(MetricNames.MODEL_CIRCUIT_TRANSITIONS, "resource", rule.getResource(), "state", current.name()).increment();
        });
        if (environment.getProperty("sentinel.nacos.enabled", Boolean.class, false)) {
            var connection = new Properties();
            connection.setProperty("serverAddr", environment.getRequiredProperty("sentinel.nacos.server-addr"));
            connection.setProperty("namespace", environment.getProperty("sentinel.nacos.namespace", ""));
            connection.setProperty("username", environment.getProperty("sentinel.nacos.username", ""));
            connection.setProperty("password", environment.getProperty("sentinel.nacos.password", ""));
            source = new NacosDataSource<>(connection,
                    environment.getProperty("sentinel.nacos.group", "SENTINEL_GROUP"), environment.getRequiredProperty("sentinel.nacos.data-id"),
                    value -> value == null || value.isBlank() ? rules : Arrays.asList(JsonUtil.fromJson(value, DegradeRule[].class)));
            DegradeRuleManager.register2Property(source.getProperty());
        }
    }
    public <T> T call(String resource, Supplier<T> operation) {
        long start = System.nanoTime();
        Entry entry = null;
        try {
            entry = SphU.entry(resource);
            return operation.get();
        } catch (BlockException e) {
            if (metrics != null) metrics.counter(MetricNames.MODEL_CALLS_BLOCKED, "resource", resource).increment();
            String code = e instanceof DegradeException ? "MODEL_CIRCUIT_OPEN" : "MODEL_ADMISSION_BLOCKED";
            log.warn("Model call rejected resource={} reason={}", resource, code);
            throw new ModelUnavailable(resource, code, 30, e);
        } catch (RuntimeException e) {
            // Overload rejection is capacity feedback, not a model execution failure.
            boolean overloaded = e instanceof ModelUnavailable unavailable && ModelUnavailable.DEFER_CODES.contains(unavailable.code());
            if (!overloaded && entry != null) Tracer.traceEntry(e, entry);
            if (metrics != null) metrics.counter(MetricNames.MODEL_CALLS_FAILURES, "resource", resource, "kind", overloaded ? "overload" : "execution").increment();
            if (e instanceof ModelUnavailable unavailable) throw unavailable.forResource(resource);
            if (e instanceof ModelCallFailure) throw e;
            throw new ModelCallFailure(resource,e);
        } finally {
            if (entry != null) entry.exit();
            if (metrics != null) metrics.timer(MetricNames.MODEL_CALLS_DURATION, "resource", resource).record(System.nanoTime()-start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
    @PreDestroy public void close() throws Exception { if (source != null) source.close(); }
}

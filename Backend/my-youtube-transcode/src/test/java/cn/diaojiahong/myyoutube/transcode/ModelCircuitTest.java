package cn.diaojiahong.myyoutube.transcode;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ModelCircuitTest {
    @org.junit.jupiter.api.AfterEach void clearRules() {
        com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager.loadRules(java.util.List.of());
    }
    public static class Overloaded extends RuntimeException { public int status() { return 503; } }
    @Test void actualSentinelOpensAfterFiveFailuresAndRecoversAfterThirtySeconds() throws Exception {
        var metrics=new SimpleMeterRegistry(); var beans=new StaticListableBeanFactory(); beans.addBean("metrics",metrics);
        var guard=new ModelCallGuard(beans.getBeanProvider(MeterRegistry.class),new MockEnvironment()); guard.initialize();
        var count=new AtomicInteger();
        for(int i=0;i<8;i++) assertThrows(cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable.class,()->guard.call("OCR",()->{ throw new cn.diaojiahong.myyoutube.common.resilience.ModelUnavailable("MODEL_QUEUE_FULL",30,null); }));
        assertEquals("ok",guard.call("OCR",()->"ok"));
        for(int i=0;i<5;i++) assertThrows(cn.diaojiahong.myyoutube.common.resilience.ModelCallFailure.class,()->guard.call("ASR",()->{count.incrementAndGet(); throw new IllegalArgumentException("execution");}));
        assertThrows(IllegalStateException.class,()->guard.call("ASR",()->{count.incrementAndGet();return "unexpected";}));
        assertEquals(5,count.get());
        Thread.sleep(30100);
        assertEquals("recovered",guard.call("ASR",()->"recovered"));
        assertEquals("closed",guard.call("ASR",()->"closed"));
        assertEquals(1,metrics.get("model.calls.blocked").tag("resource","ASR").counter().count());
        guard.close();
    }
}

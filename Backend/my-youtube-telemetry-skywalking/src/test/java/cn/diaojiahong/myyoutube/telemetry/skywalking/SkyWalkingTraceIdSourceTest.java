package cn.diaojiahong.myyoutube.telemetry.skywalking;

import cn.diaojiahong.myyoutube.common.observability.TraceIdSource;
import java.util.List;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkyWalkingTraceIdSourceTest {
    @Test
    void providerIsDiscoverable() {
        List<Class<? extends TraceIdSource>> providers = ServiceLoader.load(TraceIdSource.class)
                .stream()
                .map(ServiceLoader.Provider::type)
                .toList();
        assertEquals(java.util.List.of(SkyWalkingTraceIdSource.class), providers);
    }
}

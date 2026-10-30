package cn.diaojiahong.myyoutube.telemetry.skywalking;

import cn.diaojiahong.myyoutube.common.observability.TraceIdSource;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;

public final class SkyWalkingTraceIdSource implements TraceIdSource {
    @Override
    public String currentId() {
        return TraceContext.traceId();
    }
}

package cn.diaojiahong.myyoutube.common.observability;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

public final class PipelineTrace {
    private static final TraceIdSource SOURCE = load();
    private PipelineTrace() {}
    public static String currentId(String fallback) {
        String nativeId = null;
        try { if (SOURCE != null) nativeId = SOURCE.currentId(); }
        catch (RuntimeException | LinkageError ignored) { }
        String value = nativeId == null || nativeId.equals("N/A") || nativeId.equals("Ignored_Trace") ? fallback : nativeId;
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,128}") ? value : null;
    }
    private static TraceIdSource load() {
        try { return ServiceLoader.load(TraceIdSource.class).findFirst().orElse(null); }
        catch (ServiceConfigurationError ignored) { return null; }
    }
}

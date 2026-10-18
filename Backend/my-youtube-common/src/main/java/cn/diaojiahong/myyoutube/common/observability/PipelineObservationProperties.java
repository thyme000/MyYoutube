package cn.diaojiahong.myyoutube.common.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "my-youtube.observability")
public class PipelineObservationProperties {
    private final Toggle recording = new Toggle();
    private final Toggle delivery = new Toggle();
    private final Toggle cleanup = new Toggle();
    @Valid private final DeliverySettings batching = new DeliverySettings();
    @Valid private final MonitoringSettings monitoring = new MonitoringSettings();
    @Data public static class MonitoringSettings {
        private boolean enabled;
        @Min(1) @Max(10000) private int pageLimit = 2000;
    }
    @Data public static class DeliverySettings {
        @Min(1) @Max(2000) private int claimLimit = 200;
        @Min(1) @Max(1000) private int maxEvents = 100;
        @Min(64) @Max(4194304) private int maxBytes = 256 * 1024;
        @Min(5) @Max(3600) private int leaseSeconds = 30;
        @Min(1) @Max(2000) private int cleanupPageSize = 200;
    }
    @Data public static class Toggle { private boolean enabled; }
}

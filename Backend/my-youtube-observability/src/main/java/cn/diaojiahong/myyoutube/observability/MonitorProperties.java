package cn.diaojiahong.myyoutube.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.net.URI;
import java.util.*;

@Data
@Validated
@ConfigurationProperties("my-youtube.monitor")
public class MonitorProperties {
    private boolean enabled;
    @NotNull private URI filebeat = URI.create("http://127.0.0.1:15066");
    private boolean logsEnabled = true;
    private Map<String, String> groups = new LinkedHashMap<>();
}

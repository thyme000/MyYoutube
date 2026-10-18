package cn.diaojiahong.myyoutube.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.net.URI;
import java.nio.file.Path;

@Data
@Validated
@ConfigurationProperties("my-youtube.ingest")
public class IngestProperties {
    @NotNull private URI elasticsearch = URI.create("http://127.0.0.1:9201");
    @NotBlank private String nameserver = "127.0.0.1:9876";
    @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") private String group = "myyoutube-pipeline-ingest";
    @Pattern(regexp = "[a-z][a-z0-9-]{1,100}-") private String indexPrefix = "myyoutube-pipeline-events-";
    @NotNull private Path markerDirectory = Path.of("logs/observability/pending-dlq");
    @Min(1) @Max(32) private int maxReconsumeTimes = 16;
}

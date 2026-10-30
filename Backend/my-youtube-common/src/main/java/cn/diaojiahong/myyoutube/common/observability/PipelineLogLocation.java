package cn.diaojiahong.myyoutube.common.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import java.nio.file.Path;

@Slf4j
@AutoConfiguration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "logging.structured.format.file",
        havingValue = "cn.diaojiahong.myyoutube.common.observability.PipelineLogFormatter")
public class PipelineLogLocation {
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        String name = environment.getProperty("logging.file.name");
        if (name == null || name.isBlank()) {
            log.warn("Structured file format configured without logging.file.name; verify the Filebeat source");
            return;
        }
        Path file = Path.of(name);
        log.info("Structured log file={}", file.toAbsolutePath().normalize());
        if (!file.isAbsolute()) {
            log.warn("Log file is relative to process cwd; configure an absolute OBSERVABILITY_LOG_DIRECTORY matching the Filebeat mount");
        }
    }
}

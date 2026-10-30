package cn.diaojiahong.myyoutube.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class PipelineLogLocationTest {
    @Test
    void enabledLoggingReportsLocationWithoutRequiringBusinessComponentScanning() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PipelineLogLocation.class));
        runner.run(context -> assertFalse(context.containsBean("pipelineLogLocation")));
        runner.withPropertyValues("logging.structured.format.file=" + PipelineLogFormatter.class.getName())
                .run(context -> assertDoesNotThrow(() -> context.getBean(PipelineLogLocation.class).report()));
        runner.withPropertyValues("logging.structured.format.file=" + PipelineLogFormatter.class.getName(),
                        "logging.file.name=logs/observability/fixture.log")
                .run(context -> assertDoesNotThrow(() -> context.getBean(PipelineLogLocation.class).report()));
    }
}

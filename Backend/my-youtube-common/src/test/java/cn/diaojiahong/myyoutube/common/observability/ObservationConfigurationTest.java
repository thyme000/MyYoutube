package cn.diaojiahong.myyoutube.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class ObservationConfigurationTest {
    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PipelineObservationProperties.class)
            .withPropertyValues("spring.config.name=application-common,application");

    @Test
    void baseConfigurationDisablesAllJobsAndRecording() {
        context.run(c -> {
            assertNull(c.getStartupFailure());
            PipelineObservationProperties props = c.getBean(PipelineObservationProperties.class);
            assertFalse(props.getRecording().isEnabled());
            assertFalse(props.getDelivery().isEnabled());
            assertFalse(props.getCleanup().isEnabled());
        });
    }

    @Test
    void monitoringProfileDoesNotEnableRecordingDeliveryOrCleanup() {
        context.withPropertyValues("spring.profiles.active=monitoring").run(c -> {
            assertNull(c.getStartupFailure());
            PipelineObservationProperties props = c.getBean(PipelineObservationProperties.class);
            assertTrue(props.getMonitoring().isEnabled());
            assertFalse(props.getRecording().isEnabled());
            assertFalse(props.getDelivery().isEnabled());
            assertFalse(props.getCleanup().isEnabled());
        });
    }

    @Test
    void explicitObservationProfileEnablesPathsAndEachCanBeOverridden() {
        context.withPropertyValues("spring.profiles.active=observability").run(c -> {
            assertNull(c.getStartupFailure());
            PipelineObservationProperties props = c.getBean(PipelineObservationProperties.class);
            assertTrue(props.getRecording().isEnabled());
            assertTrue(props.getDelivery().isEnabled());
            assertTrue(props.getCleanup().isEnabled());
        });
        context.withPropertyValues(
                "spring.profiles.active=observability",
                "my-youtube.observability.delivery.enabled=false",
                "my-youtube.observability.cleanup.enabled=false").run(c -> {
            assertNull(c.getStartupFailure());
            PipelineObservationProperties props = c.getBean(PipelineObservationProperties.class);
            assertTrue(props.getRecording().isEnabled());
            assertFalse(props.getDelivery().isEnabled());
            assertFalse(props.getCleanup().isEnabled());
        });
    }
}

package cn.diaojiahong.myyoutube.content.cache;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class ContentResourceWarmupPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ContentResourceWarmupProperties.class);

    @Test
    void defaultsAndOverridesBindAtStartup() {
        context.run(application -> {
            assertNull(application.getStartupFailure());
            ContentResourceWarmupProperties properties = application.getBean(ContentResourceWarmupProperties.class);
            assertTrue(properties.isEnabled());
            assertEquals(10000, properties.getMinFans());
        });
        context.withPropertyValues("my-youtube.content-resource-cache.warmup.enabled=false",
                "my-youtube.content-resource-cache.warmup.min-fans=25000").run(application -> {
            assertNull(application.getStartupFailure());
            ContentResourceWarmupProperties properties = application.getBean(ContentResourceWarmupProperties.class);
            assertFalse(properties.isEnabled());
            assertEquals(25000, properties.getMinFans());
        });
    }

    @Test
    void zeroAllowsAllAuthorsAndNegativeThresholdFailsStartup() {
        context.withPropertyValues("my-youtube.content-resource-cache.warmup.min-fans=0")
                .run(application -> assertNull(application.getStartupFailure()));
        context.withPropertyValues("my-youtube.content-resource-cache.warmup.min-fans=-1")
                .run(application -> assertNotNull(application.getStartupFailure()));
    }
}

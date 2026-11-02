package cn.diaojiahong.myyoutube.video.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class ContentResourceCachePropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ContentResourceCacheProperties.class);

    @Test
    void defaultsAndOverridesBindAtStartup() {
        context.run(application -> {
            assertNull(application.getStartupFailure());
            ContentResourceCacheProperties properties = application.getBean(ContentResourceCacheProperties.class);
            assertEquals(10, properties.getStateTtlSeconds());
            assertEquals(1800, properties.getArtifactTtlSeconds());
            assertEquals(3, properties.getNegativeTtlSeconds());
            assertEquals(2000, properties.getLockWaitMillis());
        });
        context.withPropertyValues("my-youtube.content-resource-cache.state-ttl-seconds=8",
                "my-youtube.content-resource-cache.artifact-ttl-seconds=600",
                "my-youtube.content-resource-cache.negative-ttl-seconds=2",
                "my-youtube.content-resource-cache.lock-wait-millis=0").run(application -> {
            assertNull(application.getStartupFailure());
            ContentResourceCacheProperties properties = application.getBean(ContentResourceCacheProperties.class);
            assertEquals(8, properties.getStateTtlSeconds());
            assertEquals(600, properties.getArtifactTtlSeconds());
            assertEquals(2, properties.getNegativeTtlSeconds());
            assertEquals(0, properties.getLockWaitMillis());
        });
    }

    @Test
    void invalidTtlOrWaitTimeFailsStartup() {
        for (String setting : new String[] {"state-ttl-seconds=0", "state-ttl-seconds=61",
                "artifact-ttl-seconds=0", "artifact-ttl-seconds=86401", "negative-ttl-seconds=0",
                "negative-ttl-seconds=11", "lock-wait-millis=-1", "lock-wait-millis=5001"}) {
            context.withPropertyValues("my-youtube.content-resource-cache." + setting)
                    .run(application -> assertNotNull(application.getStartupFailure(), setting));
        }
    }

    @Test
    void upperBoundsAreAccepted() {
        context.withPropertyValues("my-youtube.content-resource-cache.state-ttl-seconds=60",
                "my-youtube.content-resource-cache.artifact-ttl-seconds=86400",
                "my-youtube.content-resource-cache.negative-ttl-seconds=10",
                "my-youtube.content-resource-cache.lock-wait-millis=5000")
                .run(application -> assertNull(application.getStartupFailure()));
    }
}

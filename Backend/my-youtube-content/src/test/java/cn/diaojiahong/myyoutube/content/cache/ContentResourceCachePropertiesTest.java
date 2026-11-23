package cn.diaojiahong.myyoutube.content.cache;

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
            assertEquals(60, properties.getShortArtifactTtlSeconds());
            assertEquals(100, properties.getArtifactMinPlayCount());
            assertEquals(3, properties.getNegativeTtlSeconds());
            assertEquals(2000, properties.getLockWaitMillis());
            assertEquals(1000, properties.getDoubleDeleteDelayMillis());
        });
        context.withPropertyValues("my-youtube.content-resource-cache.state-ttl-seconds=8",
                "my-youtube.content-resource-cache.artifact-ttl-seconds=600",
                "my-youtube.content-resource-cache.short-artifact-ttl-seconds=30",
                "my-youtube.content-resource-cache.artifact-min-play-count=250",
                "my-youtube.content-resource-cache.negative-ttl-seconds=2",
                "my-youtube.content-resource-cache.lock-wait-millis=0",
                "my-youtube.content-resource-cache.double-delete-delay-millis=500").run(application -> {
            assertNull(application.getStartupFailure());
            ContentResourceCacheProperties properties = application.getBean(ContentResourceCacheProperties.class);
            assertEquals(8, properties.getStateTtlSeconds());
            assertEquals(600, properties.getArtifactTtlSeconds());
            assertEquals(30, properties.getShortArtifactTtlSeconds());
            assertEquals(250, properties.getArtifactMinPlayCount());
            assertEquals(2, properties.getNegativeTtlSeconds());
            assertEquals(0, properties.getLockWaitMillis());
            assertEquals(500, properties.getDoubleDeleteDelayMillis());
        });
    }

    @Test
    void invalidCacheTimingFailsStartup() {
        for (String setting : new String[] {"state-ttl-seconds=0", "state-ttl-seconds=61",
                "artifact-ttl-seconds=0", "artifact-ttl-seconds=86401", "negative-ttl-seconds=0",
                "negative-ttl-seconds=11", "lock-wait-millis=-1", "lock-wait-millis=5001",
                "double-delete-delay-millis=0", "double-delete-delay-millis=5001",
                "short-artifact-ttl-seconds=0", "short-artifact-ttl-seconds=1801"}) {
            context.withPropertyValues("my-youtube.content-resource-cache." + setting)
                    .run(application -> assertNotNull(application.getStartupFailure(), setting));
        }
    }

    @Test
    void artifactAdmissionUsesTheConfiguredCumulativePlayCount() {
        var properties = new ContentResourceCacheProperties();
        properties.setArtifactMinPlayCount(250);

        assertEquals(ContentResourceReader.FillPolicy.SHORT_TTL, properties.artifactFillPolicy(null));
        assertEquals(ContentResourceReader.FillPolicy.SHORT_TTL, properties.artifactFillPolicy(249L));
        assertEquals(ContentResourceReader.FillPolicy.STANDARD, properties.artifactFillPolicy(250L));
        assertEquals(ContentResourceReader.FillPolicy.STANDARD, properties.artifactFillPolicy(251L));
    }

    @Test
    void zeroAllowsAllContentAndNegativeThresholdFailsStartup() {
        context.withPropertyValues("my-youtube.content-resource-cache.artifact-min-play-count=0").run(application -> {
            assertNull(application.getStartupFailure());
            ContentResourceCacheProperties properties = application.getBean(ContentResourceCacheProperties.class);
            assertEquals(ContentResourceReader.FillPolicy.STANDARD, properties.artifactFillPolicy(null));
            assertEquals(ContentResourceReader.FillPolicy.STANDARD, properties.artifactFillPolicy(0L));
        });
        context.withPropertyValues("my-youtube.content-resource-cache.artifact-min-play-count=-1")
                .run(application -> assertNotNull(application.getStartupFailure()));
    }

    @Test
    void upperBoundsAreAccepted() {
        context.withPropertyValues("my-youtube.content-resource-cache.state-ttl-seconds=60",
                "my-youtube.content-resource-cache.artifact-ttl-seconds=86400",
                "my-youtube.content-resource-cache.negative-ttl-seconds=10",
                "my-youtube.content-resource-cache.lock-wait-millis=5000",
                "my-youtube.content-resource-cache.double-delete-delay-millis=5000")
                .run(application -> assertNull(application.getStartupFailure()));
    }
}

package cn.diaojiahong.myyoutube.common.properties;

import cn.diaojiahong.myyoutube.config.S3ClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LocalConfigurationTest {
    private static final String[] S3 = {
            "my-youtube.s3.endpoint=http://127.0.0.1:19000",
            "my-youtube.s3.access-key=test-access",
            "my-youtube.s3.secret-key=test-secret",
            "my-youtube.s3.bucket-name=test-bucket"
    };
    private static final String SHARED = """
            my-youtube:
              s3:
                endpoint: http://127.0.0.1:19000
                access-key: test-access
                secret-key: test-secret
                bucket-name: test-bucket
            jwt:
              secret: shared-jwt
            """;
    private static final Set<String> LOCAL_RESOURCES = Set.of(
            "application-common.yml", "application-common-local.yml", "application-local.yml");
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(S3Properties.class);
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"endpoint", "access-key", "secret-key", "bucket-name"})
    void missingOrBlankStorageSettingReportsOnlyItsName(String field) {
        String key = "my-youtube.s3." + field;
        var others = Arrays.stream(S3).filter(value -> !value.startsWith(key + "=")).toArray(String[]::new);
        for (boolean blank : new boolean[]{false, true}) {
            var runner = context.withPropertyValues(others);
            if (blank) runner = runner.withPropertyValues(key + "=   ");
            runner.run(c -> assertThat(c.getStartupFailure())
                    .hasRootCauseMessage("缺少必需配置：" + key));
        }
    }

    @Test void validSettingsCreateBothStorageClientsWithoutConnectingToStorage() {
        context.withPropertyValues(S3).withUserConfiguration(S3ClientConfig.class).run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(S3Client.class).hasSingleBean(S3Presigner.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"my-youtube-llm", "my-youtube-search", "my-youtube-gateway"})
    void serviceLocalFileDoesNotHideSharedStorageSettings(String module) throws Exception {
        try (var loader = resources(SHARED, "jwt.secret: service-jwt\nmy-youtube.llm.model: service-model\n")) {
            service(module, loader).run(c -> {
                assertThat(c).hasNotFailed();
                var properties = c.getBean(S3Properties.class);
                assertThat(properties.getEndpoint()).isEqualTo("http://127.0.0.1:19000");
                assertThat(properties.getAccessKey()).isEqualTo("test-access");
                assertThat(properties.getSecretKey()).isEqualTo("test-secret");
                assertThat(properties.getBucketName()).isEqualTo("test-bucket");
                assertThat(c.getEnvironment().getProperty("jwt.secret")).isEqualTo("service-jwt");
                assertThat(c.getEnvironment().getProperty("my-youtube.llm.model")).isEqualTo("service-model");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"my-youtube-llm", "my-youtube-search", "my-youtube-gateway"})
    void serviceSpecificStorageValueOverridesSharedValue(String module) throws Exception {
        try (var loader = resources(SHARED, "my-youtube.s3.endpoint: http://127.0.0.1:19001\n")) {
            service(module, loader).run(c -> {
                assertThat(c).hasNotFailed();
                assertThat(c.getBean(S3Properties.class).getEndpoint()).isEqualTo("http://127.0.0.1:19001");
                assertThat(c.getBean(S3Properties.class).getBucketName()).isEqualTo("test-bucket");
            });
        }
    }

    @Test void optionalSharedFileCanBeReplacedByProcessConfiguration() throws Exception {
        try (var loader = resources(null, "my-youtube.llm.model: service-model\n")) {
            service("my-youtube-llm", loader).withPropertyValues(S3).run(c -> assertThat(c).hasNotFailed());
        }
    }

    @Test void missingSharedStorageConfigurationFailsWithTheRequiredKey() throws Exception {
        try (var loader = resources(null, "my-youtube.llm.model: service-model\n")) {
            service("my-youtube-llm", loader).run(c -> assertThat(c.getStartupFailure())
                    .hasRootCauseMessage("缺少必需配置：my-youtube.s3.endpoint"));
        }
    }

    private ApplicationContextRunner service(String module, ClassLoader loader) throws Exception {
        Path application = commonModule().resolveSibling(module).resolve("src/main/resources/application.yml");
        return context.withClassLoader(loader).withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=" + application.toUri());
    }

    private URLClassLoader resources(String sharedLocal, String serviceLocal) throws Exception {
        Path shared = Files.createDirectory(temp.resolve("shared"));
        Path local = Files.createDirectory(temp.resolve("service"));
        Files.copy(commonModule().resolve("src/main/resources/application-common.yml"), shared.resolve("application-common.yml"));
        if (sharedLocal != null) Files.writeString(shared.resolve("application-common-local.yml"), sharedLocal);
        Files.writeString(local.resolve("application-local.yml"), serviceLocal);
        // 同名旧文件用于复现遮蔽场景；测试不会读取开发机的真实凭据。
        Files.writeString(shared.resolve("application-local.yml"), "my-youtube.s3.endpoint: http://stale-file\n");
        return new URLClassLoader(new URL[]{local.toUri().toURL(), shared.toUri().toURL()}, getClass().getClassLoader()) {
            @Override public URL getResource(String name) {
                return LOCAL_RESOURCES.contains(name) ? findResource(name) : super.getResource(name);
            }
        };
    }

    private Path commonModule() throws Exception {
        return Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent();
    }
}

package cn.diaojiahong.myyoutube.common.prompt;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NacosPromptSourceTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PromptTemplates.class, NacosPromptSource.class);

    @Test
    void packagedDefaultsNeedNoNacosConnection() {
        context.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(0, ctx.getBeansOfType(NacosPromptSource.class).size());
            assertNotNull(ctx.getBean(PromptTemplates.class).snapshot());
        });
    }

    @Test
    void listenerHotReloadsAndDeletionOrInvalidUpdateRetainsLastGoodVersion() throws Exception {
        ConfigService client = mock(ConfigService.class);
        String defaults = PromptTemplatesTest.defaults();
        when(client.getConfigAndSignListener(eq("model-prompts.yaml"), eq("PROMPTS_GROUP"), eq(5000L), any()))
                .thenReturn(defaults);
        try (MockedStatic<NacosFactory> factory = mockStatic(NacosFactory.class)) {
            factory.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenReturn(client);
            context.withPropertyValues(
                    "my-youtube.prompts.nacos.enabled=true",
                    "my-youtube.prompts.nacos.server-addr=localhost:8848")
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure());
                        PromptTemplates registry = ctx.getBean(PromptTemplates.class);
                        ArgumentCaptor<Listener> listener = ArgumentCaptor.forClass(Listener.class);
                        verify(client).getConfigAndSignListener(
                                eq("model-prompts.yaml"),
                                eq("PROMPTS_GROUP"),
                                eq(5000L),
                                listener.capture());
                        String before = registry.snapshot().version();
                        listener.getValue().receiveConfigInfo(defaults.replace("将本批搜索词按语义分组", "请将本批搜索词按搜索意图分组"));
                        PromptTemplates.Snapshot accepted = registry.snapshot();
                        assertNotEquals(before, accepted.version());
                        assertTrue(accepted.render("hot-search-group-system").startsWith("请将本批搜索词按搜索意图分组"));
                        listener.getValue().receiveConfigInfo("templates: {}");
                        listener.getValue().receiveConfigInfo(null);
                        assertSame(accepted, registry.snapshot());
                        listener.getValue().receiveConfigInfo(defaults);
                        assertEquals(before, registry.snapshot().version());
                    });
            verify(client).removeListener(eq("model-prompts.yaml"), eq("PROMPTS_GROUP"), any());
            verify(client).shutDown();
        }
    }

    @Test
    void enabledButMissingConfigurationFailsStartupAndClosesClient() throws Exception {
        ConfigService client = mock(ConfigService.class);
        try (MockedStatic<NacosFactory> factory = mockStatic(NacosFactory.class)) {
            factory.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenReturn(client);
            context.withPropertyValues(
                    "my-youtube.prompts.nacos.enabled=true",
                    "my-youtube.prompts.nacos.server-addr=localhost:8848")
                    .run(ctx -> assertNotNull(ctx.getStartupFailure()));
            verify(client).shutDown();
        }
    }
}

package cn.diaojiahong.myyoutube.common.media;

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

class NacosFrameSettingsSourceTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(FrameProcessingSettings.class, NacosFrameSettingsSource.class);

    @Test
    void disabledSourceDoesNotConnectAndUsesConfiguredDefaults() {
        context.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(ctx.getBeansOfType(NacosFrameSettingsSource.class).isEmpty());
            assertEquals(10, ctx.getBean(FrameProcessingSettings.class).snapshot().maxFrames());
        });
    }

    @Test
    void listenerAppliesUpdatesRollbackAndRetainsGoodConfigAfterInvalidOrDeletedUpdate() throws Exception {
        ConfigService client = mock(ConfigService.class);
        String defaults = FrameProcessingSettingsTest.defaults();
        when(client.getConfigAndSignListener(eq("frame-processing.yaml"), eq("MEDIA_GROUP"), eq(5000L), any()))
                .thenReturn(defaults);
        try (MockedStatic<NacosFactory> factory = mockStatic(NacosFactory.class)) {
            factory.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenReturn(client);
            context.withPropertyValues(
                    "my-youtube.frame-processing.nacos.enabled=true",
                    "my-youtube.frame-processing.nacos.server-addr=localhost:8848")
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure());
                        FrameProcessingSettings settings = ctx.getBean(FrameProcessingSettings.class);
                        ArgumentCaptor<Listener> listener = ArgumentCaptor.forClass(Listener.class);
                        verify(client).getConfigAndSignListener(
                                eq("frame-processing.yaml"),
                                eq("MEDIA_GROUP"),
                                eq(5000L),
                                listener.capture());
                        listener.getValue().receiveConfigInfo(defaults.replace("max-frames: 10", "max-frames: 3"));
                        FrameProcessingSettings.Snapshot accepted = settings.snapshot();
                        assertEquals(3, accepted.maxFrames());
                        listener.getValue().receiveConfigInfo("max-frames: 0");
                        listener.getValue().receiveConfigInfo(null);
                        assertSame(accepted, settings.snapshot());
                        listener.getValue().receiveConfigInfo(defaults);
                        assertEquals(10, settings.snapshot().maxFrames());
                    });
            verify(client).removeListener(eq("frame-processing.yaml"), eq("MEDIA_GROUP"), any());
            verify(client).shutDown();
        }
    }

    @Test
    void enabledSourceRequiresValidInitialConfigurationAndClosesOnFailure() {
        ConfigService client = mock(ConfigService.class);
        try (MockedStatic<NacosFactory> factory = mockStatic(NacosFactory.class)) {
            factory.when(() -> NacosFactory.createConfigService(any(Properties.class))).thenReturn(client);
            context.withPropertyValues(
                    "my-youtube.frame-processing.nacos.enabled=true",
                    "my-youtube.frame-processing.nacos.server-addr=localhost:8848")
                    .run(ctx -> assertNotNull(ctx.getStartupFailure()));
            try {
                verify(client).shutDown();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}

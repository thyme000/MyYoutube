package cn.diaojiahong.myyoutube.common.media;

import com.alibaba.nacos.api.NacosFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "frame.nacos.address", matches = ".+")
class LiveNacosFrameSettingsTest {
    @Test void realNacosPushesUpdatesAndRollbackToTwoIndependentInstances() throws Exception {
        String address = System.getProperty("frame.nacos.address");
        String dataId = "frame-settings-test-" + UUID.randomUUID() + ".yaml";
        String group = "CODEX_FRAME_SETTINGS_TEST";
        var connection = new Properties(); connection.setProperty("serverAddr", address);
        var publisher = NacosFactory.createConfigService(connection);
        var first = new FrameProcessingSettings();
        var second = new FrameProcessingSettings();
        var environment = new MockEnvironment().withProperty("my-youtube.frame-processing.nacos.server-addr", address)
                .withProperty("my-youtube.frame-processing.nacos.data-id", dataId)
                .withProperty("my-youtube.frame-processing.nacos.group", group);
        var firstSource = new NacosFrameSettingsSource(first, environment);
        var secondSource = new NacosFrameSettingsSource(second, environment);
        try {
            String defaults = FrameProcessingSettingsTest.defaults();
            assertTrue(publisher.publishConfig(dataId, group, defaults, "yaml"));
            await(() -> {
                try { return defaults.equals(publisher.getConfig(dataId, group, 1000)); }
                catch (Exception e) { return false; }
            });
            firstSource.initialize(); secondSource.initialize();
            var running = first.snapshot();
            assertEquals(10, running.maxFrames());
            String updated = defaults.replace("max-frames: 10", "max-frames: 3")
                    .replace("ocr-batch-size: 2", "ocr-batch-size: 1").replace("cover-max-candidates: 8", "cover-max-candidates: 2")
                    .replace("min-success-percent: 80", "min-success-percent: 60");
            assertTrue(publisher.publishConfig(dataId, group, updated, "yaml"));
            await(() -> first.snapshot().maxFrames() == 3 && second.snapshot().maxFrames() == 3
                    && first.snapshot().minSuccessPercent() == 60 && second.snapshot().minSuccessPercent() == 60);
            assertEquals(1, first.snapshot().ocrBatchSize());
            assertEquals(2, second.snapshot().coverMaxCandidates());
            assertEquals(10, running.maxFrames());
            assertEquals(80, running.minSuccessPercent());
            assertEquals(2, second.snapshot().plan(50000, 0).minimumCount());
            assertTrue(publisher.publishConfig(dataId, group, defaults, "yaml"));
            await(() -> first.snapshot().maxFrames() == 10 && second.snapshot().maxFrames() == 10
                    && first.snapshot().minSuccessPercent() == 80 && second.snapshot().minSuccessPercent() == 80);
            assertEquals(2, first.snapshot().ocrBatchSize());
        } finally {
            try { firstSource.close(); }
            finally {
                try { secondSource.close(); }
                finally {
                    try { assertTrue(publisher.removeConfig(dataId, group)); }
                    finally { publisher.shutDown(); }
                }
            }
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "Nacos push did not arrive within 20 seconds");
    }
}

package cn.diaojiahong.myyoutube.common.media;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FrameProcessingSettingsTest {
    static String defaults() throws Exception {
        try (var input = new ClassPathResource("media/frame-processing.yaml").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void defaultsLimitLongVideosToTenAndLocalOverridesAreValidated() throws Exception {
        var settings = new FrameProcessingSettings();
        var before = settings.snapshot();
        settings.replace(defaults());
        assertEquals(before, settings.snapshot());
        assertEquals(10, settings.snapshot().count(1200000, 0));
        assertEquals(6, settings.snapshot().count(30000, 0));
        assertEquals(5, settings.snapshot().plan(30000, 0).minimumCount());
        var local = new FrameProcessingSettings(new MockEnvironment()
                .withProperty("my-youtube.frame-processing.interval-seconds", "10")
                .withProperty("my-youtube.frame-processing.max-frames", "3")
                .withProperty("my-youtube.frame-processing.min-success-percent", "60"));
        assertEquals(3, local.snapshot().count(1200000, 0));
        assertEquals(2, local.snapshot().plan(1200000, 0).minimumCount());
    }

    @Test void replacementIsAtomicAndExistingSnapshotDoesNotChange() throws Exception {
        var settings = new FrameProcessingSettings();
        var running = settings.snapshot();
        settings.replace(defaults().replace("interval-seconds: 5", "interval-seconds: 10").replace("max-frames: 10", "max-frames: 4")
                .replace("cover-max-candidates: 8", "cover-max-candidates: 3").replace("ocr-batch-size: 2", "ocr-batch-size: 1")
                .replace("review-batch-size: 4", "review-batch-size: 2").replace("min-success-percent: 80", "min-success-percent: 60"));
        assertEquals(new FrameProcessingSettings.Snapshot(10, 4, 3, 1, 2, 60), settings.snapshot());
        assertEquals(new FrameProcessingSettings.Snapshot(5, 10, 8, 2, 4, 80), running);
        assertEquals(6, running.count(30000, 0));
        assertEquals(3, settings.snapshot().count(30000, 0));
    }

    @Test void invalidOrPartialUpdatesRetainTheWholeLastGoodSnapshot() throws Exception {
        var settings = new FrameProcessingSettings();
        String defaults = defaults();
        var valid = settings.snapshot();
        for (String yaml : List.of("", "max-frames: 2", defaults + "other: 1", defaults + "max-frames: 3",
                defaults.replace("interval-seconds: 5", "interval-seconds: 0"), defaults.replace("max-frames: 10", "max-frames: 65"),
                defaults.replace("max-frames: 10", "max-frames: 1.5"), defaults.replace("ocr-batch-size: 2", "ocr-batch-size: 0"),
                defaults.replace("cover-max-candidates: 8", "cover-max-candidates: 0"), defaults.replace("review-batch-size: 4", "review-batch-size: 9"),
                defaults.replace("min-success-percent: 80", "min-success-percent: 0"),
                defaults.replace("min-success-percent: 80", "min-success-percent: 101"),
                defaults.replace("min-success-percent: 80", "min-success-percent: 80.5"),
                defaults.replace("min-success-percent: 80\n", ""))) {
            assertThrows(RuntimeException.class, () -> settings.replace(yaml));
            assertSame(valid, settings.snapshot());
        }
        assertThrows(IllegalArgumentException.class, () -> settings.replace(null));
        assertSame(valid, settings.snapshot());
    }
}

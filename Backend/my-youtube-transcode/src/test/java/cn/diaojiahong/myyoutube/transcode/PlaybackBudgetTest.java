package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.preparation.MediaPreparationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackBudgetTest {
    @Test
    void durationBudgetGrowsContinuouslyAndIncludesStartupAllowance() {
        MediaPreparationProperties.Playback playback = new MediaPreparationProperties().getPlayback();
        assertEquals(Duration.ofSeconds(180), playback.totalTimeout(20000));
        assertEquals(Duration.ofMinutes(32), playback.totalTimeout(Duration.ofMinutes(10).toMillis()));
        assertEquals(Duration.ofMinutes(362), playback.totalTimeout(Duration.ofHours(2).toMillis()));
    }

    @ParameterizedTest
    @CsvSource({"1,1,1", "2,1,1", "4,1,3", "8,1,7", "8,2,3", "8,4,1", "2,4,1"})
    void automaticThreadsShareCpuAllowanceAcrossDraftWorkers(int processors, int concurrency, int expected) {
        MediaPreparationProperties.Playback playback = new MediaPreparationProperties().getPlayback();
        assertEquals(expected, playback.resolveThreads(processors, concurrency));
    }

    @Test
    void explicitThreadsOverrideAutomaticAllowance() {
        MediaPreparationProperties properties = new MediaPreparationProperties();
        properties.getPlayback().setThreads(4);
        properties.validate();
        assertEquals(4, properties.getPlayback().resolveThreads(2, 2));
    }

    @Test
    void configurableFactorAndStartupAllowanceAreBothApplied() {
        MediaPreparationProperties properties = new MediaPreparationProperties();
        properties.getPlayback().setDurationMultiplier(0.5);
        properties.getPlayback().setStartupTimeout(Duration.ofMinutes(1));
        properties.validate();
        assertEquals(Duration.ofMinutes(6), properties.getPlayback().totalTimeout(Duration.ofMinutes(10).toMillis()));
        assertThrows(IllegalArgumentException.class, () -> properties.getPlayback().totalTimeout(0));
    }

    @Test
    void invalidTimingConfigurationIsRejectedAtStartup() {
        for (double factor : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            MediaPreparationProperties properties = new MediaPreparationProperties();
            properties.getPlayback().setDurationMultiplier(factor);
            assertThrows(IllegalStateException.class, properties::validate);
        }
        MediaPreparationProperties properties = new MediaPreparationProperties();
        properties.getPlayback().setThreads(-1);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.getPlayback().setThreads(0);
        properties.getPlayback().setStartupTimeout(Duration.ZERO);
        assertThrows(IllegalStateException.class, properties::validate);
        properties.getPlayback().setStartupTimeout(Duration.ofMinutes(2));
        properties.getPlayback().setStallTimeout(Duration.ofSeconds(-1));
        assertThrows(IllegalStateException.class, properties::validate);
    }
}

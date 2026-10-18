package cn.diaojiahong.myyoutube.common.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class VideoFrameSamplingTest {
    @ParameterizedTest
    @CsvSource({"1,1", "3000,1", "5000,1", "5001,2", "30000,6", "50000,10", "50001,10", "120000,10",
            "315001,10", "320000,10", "1200000,10", "9223372036854775807,10"})
    void durationDeterminesCountWithBoundedCost(long duration, int count) {
        assertEquals(count, VideoFrameSampling.count(duration, 0));
    }

    @Test void knownSourceFrameCountLimitsDuplicateSampling() {
        assertEquals(1, VideoFrameSampling.count(60000, 1));
        assertEquals(3, VideoFrameSampling.count(60000, 3));
        assertThrows(IllegalArgumentException.class, () -> VideoFrameSampling.count(0, 0));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,2", "3,3", "4,4", "5,4", "6,5", "7,6", "8,7", "9,8", "10,8", "64,52"})
    void minimumRoundsUpForEachTarget(int target, int minimum) {
        assertEquals(minimum, VideoFrameSampling.plan(target, 80).minimumCount());
    }

    @Test void countCoverageAndSlotIdentityMustAllPass() {
        var plan = VideoFrameSampling.plan(10, 80);
        var accepted = java.util.stream.IntStream.range(0, 10).filter(i -> i != 2 && i != 7).boxed().toList();
        assertDoesNotThrow(() -> plan.validate(accepted));
        assertEquals(java.util.List.of(2, 7), plan.missing(accepted));
        assertThrows(IllegalArgumentException.class, () -> plan.validate(accepted.subList(0, 7)));
        assertThrows(IllegalArgumentException.class, () -> plan.validate(java.util.List.of(0, 1, 4, 5, 6, 7, 8, 9)));
        assertThrows(IllegalArgumentException.class, () -> plan.validate(java.util.List.of(0, 0, 2, 3, 4, 5, 6, 7, 8, 9)));
        assertThrows(IllegalArgumentException.class, () -> plan.validate(java.util.List.of(1, 0, 2, 3, 4, 5, 6, 7, 8, 9)));
        assertThrows(IllegalArgumentException.class, () -> plan.validate(java.util.List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 10)));
        assertThrows(IllegalArgumentException.class, () -> new VideoFrameSampling.Plan(10, 7, 80));
        assertThrows(IllegalArgumentException.class, () -> VideoFrameSampling.plan(10, 0));
        assertThrows(IllegalArgumentException.class, () -> VideoFrameSampling.plan(65, 80));
    }

    @Test void cappedSamplesCoverBothEndsAndEqualIntervalsAcrossWholeVideo() {
        assertEquals(0.5, VideoFrameSampling.ratio(0, 1));
        assertEquals(0, VideoFrameSampling.ratio(0, 64));
        assertEquals(1, VideoFrameSampling.ratio(63, 64));
        for (int i = 1; i < 64; i++) {
            assertEquals(1200000.0 / 63, 1200000 * (VideoFrameSampling.ratio(i, 64)
                    - VideoFrameSampling.ratio(i - 1, 64)), 0.00001);
        }
        assertThrows(IllegalArgumentException.class, () -> VideoFrameSampling.ratio(0, 0));
        assertThrows(IllegalArgumentException.class, () -> VideoFrameSampling.ratio(64, 64));
    }
}

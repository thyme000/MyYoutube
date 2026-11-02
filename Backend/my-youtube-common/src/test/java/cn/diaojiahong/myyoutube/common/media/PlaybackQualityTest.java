package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackQuality;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackQualityTest {
    @ParameterizedTest
    @CsvSource({"3840,2160,4", "1920,1080,3", "1280,720,2", "854,480,1", "640,360,1",
            "1080,1920,3", "720,1280,2", "1920,800,3"})
    void generatesSourceAndOnlyStrictlyLowerQualitiesForBothOrientations(int width, int height, int count) {
        List<PlaybackQuality> qualities = PlaybackQuality.forVideo(width, height);
        assertEquals(count, qualities.size());
        assertTrue(qualities.stream().filter(quality -> quality != PlaybackQuality.SOURCE)
                .allMatch(quality -> quality.code() < Math.min(width, height)));
        assertEquals(qualities.size(), qualities.stream().distinct().count());
        assertTrue(qualities.stream().allMatch(quality -> PlaybackQuality.fromCode(quality.code()) == quality));
        assertSame(PlaybackQuality.SOURCE, qualities.get(qualities.size() - 1));
        assertEquals(1, qualities.stream().filter(quality -> quality == PlaybackQuality.SOURCE).count());
        assertSame(PlaybackQuality.SOURCE, PlaybackQuality.fromCode(0));
        assertNull(PlaybackQuality.fromCode(144));
    }
}

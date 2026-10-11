package cn.diaojiahong.myyoutube.common.media;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DynamicTimelineTest {
    @Test void variableDurationsSubtractOnlyAdjacentOverlaps() {
        var schedule = DynamicTimeline.schedule(List.of(3000L, 4000L, 2000L), 800);
        assertEquals(List.of(0L, 2200L, 5400L), schedule.startsMs());
        assertEquals(7400, schedule.exportDurationMs());
        assertThrows(UnsupportedOperationException.class, () -> schedule.startsMs().add(1L));
    }

    @Test void cutAndSingleImageHaveNoExtraLoopOverlap() {
        assertEquals(7000, DynamicTimeline.schedule(List.of(3000L, 4000L), 0).exportDurationMs());
        assertEquals(3000, DynamicTimeline.schedule(List.of(3000L), 800).exportDurationMs());
    }

    @Test void invalidDurationsAndOverflowFail() {
        assertThrows(IllegalArgumentException.class, () -> DynamicTimeline.schedule(List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> DynamicTimeline.schedule(List.of(800L), 800));
        assertThrows(IllegalArgumentException.class, () -> DynamicTimeline.schedule(List.of(0L), 0));
        assertThrows(IllegalArgumentException.class, () -> DynamicTimeline.schedule(List.of(3000L), -1));
        assertThrows(IllegalArgumentException.class, () -> DynamicTimeline.schedule(List.of(Long.MAX_VALUE, 1L), 0));
    }
}

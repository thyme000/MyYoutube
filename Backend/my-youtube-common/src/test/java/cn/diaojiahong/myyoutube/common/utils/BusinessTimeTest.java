package cn.diaojiahong.myyoutube.common.utils;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class BusinessTimeTest {
    @Test
    void businessWallTimeMapsToKnownInstantAndBack() {
        LocalDateTime wallTime = LocalDateTime.parse("2026-10-25T10:00:00");
        Instant instant = Instant.parse("2026-10-25T02:00:00Z");
        assertEquals(instant, BusinessTime.toInstant(wallTime));
        assertEquals(wallTime, BusinessTime.fromInstant(instant));
    }

    @Test
    void businessNowRepresentsTheCurrentInstant() {
        Instant before = Instant.now();
        Instant actual = BusinessTime.toInstant(BusinessTime.now());
        Instant after = Instant.now();
        assertFalse(actual.isBefore(before));
        assertFalse(actual.isAfter(after));
    }
}

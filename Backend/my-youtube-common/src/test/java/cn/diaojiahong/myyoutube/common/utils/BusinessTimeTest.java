package cn.diaojiahong.myyoutube.common.utils;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class BusinessTimeTest {
    @Test void businessWallTimeMapsToKnownInstantAndBack() {
        var wallTime = LocalDateTime.parse("2026-10-25T10:00:00");
        var instant = Instant.parse("2026-10-25T02:00:00Z");
        assertEquals(instant, BusinessTime.toInstant(wallTime));
        assertEquals(wallTime, BusinessTime.fromInstant(instant));
    }
    @Test void businessNowRepresentsTheCurrentInstant() {
        var before = Instant.now(); var actual = BusinessTime.toInstant(BusinessTime.now()); var after = Instant.now();
        assertFalse(actual.isBefore(before)); assertFalse(actual.isAfter(after));
    }
}

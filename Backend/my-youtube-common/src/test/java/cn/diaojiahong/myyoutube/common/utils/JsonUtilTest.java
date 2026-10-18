package cn.diaojiahong.myyoutube.common.utils;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class JsonUtilTest {
    record History(Long id, LocalDateTime createdAt, LocalDateTime completedAt) {}

    @Test void historyTimesRoundTripWhileLargeIdsRemainStrings() {
        var original = new History(9007199254740993L, LocalDateTime.parse("2026-10-18T12:34:56.123456"), null);
        String json = JsonUtil.toJson(original);
        assertTrue(json.contains("\"id\":\"9007199254740993\""));
        assertTrue(json.contains("\"createdAt\":\"2026-10-18T12:34:56.123456\""));
        assertEquals(original, JsonUtil.fromJson(json, History.class));
    }
}

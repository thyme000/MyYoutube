package cn.diaojiahong.myyoutube.search.recall;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LatestFuserTimeTest {
    @Test void mixedTimestampFormatsUseTheSameInstantForLatestOrdering() throws Exception {
        RecallSearchClient client = mock(RecallSearchClient.class);
        when(client.createTimes(anyList())).thenReturn(Map.of(
                "1", "2026-10-25 10:00:00",
                "2", Instant.parse("2026-10-25T02:00:01Z").toEpochMilli(),
                "3", "2026-10-25T09:59:59+08:00"));
        assertEquals(List.of("2", "1", "3"), new LatestFuser(client).fuse(null,
                List.of(List.of("1", "2", "3"))));
    }
}

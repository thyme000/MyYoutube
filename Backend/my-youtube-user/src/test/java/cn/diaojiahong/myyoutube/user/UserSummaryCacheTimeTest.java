package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSummaryCacheTimeTest {
    @Test void cacheReadsEpochAsBusinessWallTime() {
        var redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(anyString())).thenReturn(Map.of("id", "9", "createTime",
                Long.toString(Instant.parse("2026-10-25T02:00:00Z").toEpochMilli())));
        var actual = new UserSummaryCache(redis).get(9L, id -> { throw new AssertionError("Unexpected cache miss"); });
        assertEquals(LocalDateTime.parse("2026-10-25T10:00:00"), actual.getCreateTime());
    }

    @Test void cacheWritesBusinessWallTimeAsExactEpoch() {
        var redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes); when(hashes.entries(anyString())).thenReturn(Map.of());
        var user = new UserSummary(); user.setId(9L); user.setCreateTime(LocalDateTime.parse("2026-10-25T10:00:00"));
        new UserSummaryCache(redis).get(9L, id -> user);
        var callback = ArgumentCaptor.forClass(RedisCallback.class);
        verify(redis).executePipelined(callback.capture());
        var connection = mock(RedisConnection.class, RETURNS_DEEP_STUBS); callback.getValue().doInRedis(connection);
        ArgumentCaptor<Map<byte[], byte[]>> written = ArgumentCaptor.forClass(Map.class);
        verify(connection.hashCommands()).hMSet(any(byte[].class), written.capture());
        assertTrue(written.getValue().keySet().stream()
                .noneMatch(key -> "contentCount".equals(new String(key, StandardCharsets.UTF_8))));
        var epoch = written.getValue().entrySet().stream()
                .filter(e -> new String(e.getKey(), StandardCharsets.UTF_8).equals("createTime"))
                .map(e -> new String(e.getValue(), StandardCharsets.UTF_8)).findFirst().orElseThrow();
        assertEquals(Long.toString(Instant.parse("2026-10-25T02:00:00Z").toEpochMilli()), epoch);
    }
}

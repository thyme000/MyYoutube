package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.user.cache.UserCheckInBitmapCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCheckInBitmapCacheTest {
    @Test
    void initializesWithDatabaseHistoryAndReadsRedisBitOffsetsAcrossBytes() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisStringCommands strings = mock(RedisStringCommands.class);
        when(connection.stringCommands()).thenReturn(strings);
        when(redis.execute(any(RedisCallback.class))).thenAnswer(call -> {
            RedisCallback<?> callback = call.getArgument(0);
            return callback.doInRedis(connection);
        });

        var cache = new UserCheckInBitmapCache(redis);
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end = LocalDate.of(2024, 12, 31);
        List<LocalDate> dates = List.of(start, start.plusDays(8), end);
        byte[] key = "user:check-in:bitmap:1:2024".getBytes(StandardCharsets.UTF_8);
        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(end.atTime(23, 59));
            assertEquals(dates, cache.dates(1L, start, () -> dates));
        }

        ArgumentCaptor<byte[]> bitmap = ArgumentCaptor.forClass(byte[].class);
        verify(strings).set(eq(key), bitmap.capture(), eq(Expiration.milliseconds(60_000)),
                eq(SetOption.ifAbsent()));
        assertEquals(46, bitmap.getValue().length);
        assertEquals((byte) 0x80, bitmap.getValue()[0]);
        assertEquals((byte) 0x80, bitmap.getValue()[1]);
        assertEquals((byte) 0x04, bitmap.getValue()[45]);

        when(strings.get(key)).thenReturn(bitmap.getValue());
        assertEquals(dates, cache.dates(1L, start, () -> {
            throw new AssertionError("缓存命中时不应回查数据库");
        }));
    }
}

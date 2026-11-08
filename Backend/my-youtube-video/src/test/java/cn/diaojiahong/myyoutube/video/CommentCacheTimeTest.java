package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.comment.cache.CommentListCacheImpl;
import cn.diaojiahong.myyoutube.comment.cursor.CommentCursor;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentTab;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.mapper.ContentCommentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommentCacheTimeTest {
    /** 让 executePipelined 的回调真的跑起来(对 mock 连接逐条执行), 否则回调体内的命令不会发生 */
    private void pipelineExecutesForReal(StringRedisTemplate redis, RedisStringCommands commands,
                                         RedisKeyCommands keyCommands) {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.stringCommands()).thenReturn(commands);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(inv -> {
            RedisCallback<?> callback = inv.getArgument(0);
            callback.doInRedis(connection);
            return List.of();
        });
    }

    @Test
    void latestCacheCursorUsesBusinessTimeInsteadOfJvmTimeZone() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ContentCommentMapper rows = mock(ContentCommentMapper.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        RedisStringCommands commands = mock(RedisStringCommands.class);
        RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
        pipelineExecutesForReal(redis, commands, keyCommands);
        var comment = new ContentComment();
        comment.setId(5L);
        comment.setCreateTime(LocalDateTime.parse("2026-10-25T10:00:00"));
        when(rows.selectList(any())).thenReturn(List.of(comment));
        var cache = new CommentListCacheImpl(redis, rows);
        ReflectionTestUtils.setField(cache, "threshold", 1);
        ReflectionTestUtils.setField(cache, "latestCap", 20);
        ReflectionTestUtils.setField(cache, "latestTtlSeconds", 5L);
        cache.build(1L, CommentTab.LATEST, 1);
        String expected = CommentCursor.of(Instant.parse("2026-10-25T02:00:00Z").getEpochSecond(), 5L).encode();

        ArgumentCaptor<byte[]> listValue = ArgumentCaptor.forClass(byte[].class);
        verify(commands).set(any(), listValue.capture(), eq(Expiration.from(Duration.ofSeconds(5))), eq(SetOption.UPSERT));
        assertEquals(expected, new String(listValue.getValue(), StandardCharsets.UTF_8));
        verify(keyCommands).del(any());
    }

    @Test
    void redisReadFailureDegradesToDbInsteadOfFailingThePage() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));
        var cache = new CommentListCacheImpl(redis, mock(ContentCommentMapper.class));

        assertNull(cache.get(1L, CommentTab.LATEST));
    }

    @Test
    void redisFailureDuringBuildDoesNotPropagateToTheReadPath() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));
        var cache = new CommentListCacheImpl(redis, mock(ContentCommentMapper.class));
        ReflectionTestUtils.setField(cache, "threshold", 1);

        assertDoesNotThrow(() -> cache.build(1L, CommentTab.LATEST, 1));
    }

    @Test
    void pipelineFailureDuringBuildDegradesAndTheLockSelfHealsByTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(redis.executePipelined(any(RedisCallback.class))).thenThrow(new RedisConnectionFailureException("down"));
        var comment = new ContentComment();
        comment.setId(5L);
        comment.setCreateTime(LocalDateTime.parse("2026-10-25T10:00:00"));
        ContentCommentMapper rows = mock(ContentCommentMapper.class);
        when(rows.selectList(any())).thenReturn(List.of(comment));
        var cache = new CommentListCacheImpl(redis, rows);
        ReflectionTestUtils.setField(cache, "threshold", 1);

        assertDoesNotThrow(() -> cache.build(1L, CommentTab.LATEST, 1));
    }

    @Test
    void corruptListSelfHealsByDeletingTheKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("garbage");
        var cache = new CommentListCacheImpl(redis, mock(ContentCommentMapper.class));

        assertNull(cache.get(1L, CommentTab.LATEST));
        verify(redis).delete(anyString());
    }
}

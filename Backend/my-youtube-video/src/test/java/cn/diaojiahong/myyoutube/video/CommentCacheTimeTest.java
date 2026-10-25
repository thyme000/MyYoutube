package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.comment.cache.CommentListCacheImpl;
import cn.diaojiahong.myyoutube.comment.cursor.CommentCursor;
import cn.diaojiahong.myyoutube.comment.domain.enums.CommentTab;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.mapper.ContentCommentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;

class CommentCacheTimeTest {
    @Test void latestCacheCursorUsesBusinessTimeInsteadOfJvmTimeZone() {
        var redis = mock(StringRedisTemplate.class); var rows = mock(ContentCommentMapper.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        var comment = new ContentComment(); comment.setId(5L); comment.setCreateTime(LocalDateTime.parse("2026-10-25T10:00:00"));
        when(rows.selectList(any())).thenReturn(List.of(comment));
        var cache = new CommentListCacheImpl(redis, rows);
        ReflectionTestUtils.setField(cache, "threshold", 1);
        ReflectionTestUtils.setField(cache, "latestCap", 20);
        ReflectionTestUtils.setField(cache, "latestTtlSeconds", 5L);
        cache.build(1L, CommentTab.LATEST, 1);
        String expected = CommentCursor.of(Instant.parse("2026-10-25T02:00:00Z").getEpochSecond(), 5L).encode();
        verify(values).set(anyString(), eq(expected), eq(Duration.ofSeconds(5)));
    }
}

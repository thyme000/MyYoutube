package cn.diaojiahong.myyoutube.follow.cache;

import cn.diaojiahong.myyoutube.user.api.FollowPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.diaojiahong.myyoutube.user.api.FollowSnapshotKey;

class FollowSnapshotCacheTest {
    private final FollowPort followPort = mock(FollowPort.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final FollowSnapshotCache cache = new FollowSnapshotCache(followPort, redis);

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(valueOps);
        ReflectionTestUtils.setField(cache, "ttlSeconds", 5L);
    }

    private String key(long userId) {
        return FollowSnapshotKey.key(userId);
    }

    @Test
    void missReachesFollowPortThenCachesWithTtl() {
        when(valueOps.get(key(7))).thenReturn(null);
        when(followPort.getFollowIds(7L)).thenReturn(List.of(9L, 3L));

        assertEquals(List.of(9L, 3L), cache.getFollowIds(7L));

        verify(valueOps).set(key(7), "9,3", Duration.ofSeconds(5));
        verify(followPort).getFollowIds(7L);
        verifyNoMoreInteractions(followPort);
    }

    @Test
    void hitAnswersFromCacheWithoutTouchingFollowPort() {
        when(valueOps.get(key(7))).thenReturn("9,3");

        assertEquals(List.of(9L, 3L), cache.getFollowIds(7L));
        verifyNoInteractions(followPort);
    }

    @Test
    void cachedEmptyListAnswersWithoutReachingFollowPort() {
        when(valueOps.get(key(7))).thenReturn("");

        assertEquals(List.of(), cache.getFollowIds(7L));
        verifyNoInteractions(followPort);
    }

    @Test
    void followStateBackfillAnswersFromTheSameSnapshotInInputOrder() {
        when(valueOps.get(key(7))).thenReturn("9,3");

        assertEquals(List.of(true, false, true), cache.isFollowOnList(7L, List.of(9L, 8L, 3L)));
        verifyNoInteractions(followPort);
    }

    @Test
    void emptyAuthorListShortCircuitsWithoutCacheOrRemote() {
        assertEquals(List.of(), cache.isFollowOnList(7L, List.of()));
        verifyNoInteractions(redis, followPort);
    }
}

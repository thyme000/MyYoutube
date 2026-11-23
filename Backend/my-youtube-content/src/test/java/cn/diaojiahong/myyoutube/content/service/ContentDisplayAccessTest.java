package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCacheProperties;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceRegion;
import cn.diaojiahong.myyoutube.content.domain.ContentAccessSnapshot;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.content.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewStatus;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentDisplayAccessTest {
    private final ContentMapper contents = mock(ContentMapper.class);
    private final AudioMapper audios = mock(AudioMapper.class);
    private final AfterCommitNotifications notifications = mock(AfterCommitNotifications.class);
    private final AudioContentAccess audioAccess = new AudioContentAccess(audios, notifications);
    private final ContentAccessService liveAccess = new ContentAccessService(contents, audioAccess, org.mockito.Mockito.mock(UserBlockPort.class));
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final ContentResourceCacheProperties properties = new ContentResourceCacheProperties();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ContentResourceCache cache = new ContentResourceCache(redis, redisson, properties, metrics);
    private final ContentDisplayAccess display = new ContentDisplayAccess(cache,
            new ContentAccessSnapshotLoader(contents, audioAccess), liveAccess);
    private final Map<String, String> entries = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private final String key = ContentResourceRegion.CONTENT_STATE.key("1");
    private final Content content = new Content().setId(1L).setAuthorId(7L).setAudioId(20L)
            .setType(ContentTypeEnum.DYNAMIC).setStatus(ContentStatusEnum.PUBLISHED).setIsDelete(0)
            .setVisibility("public").setCreateTime(LocalDateTime.of(2026, 11, 3, 12, 0));
    private final Audio audio = new Audio().setId(20L).setReviewStatus(AudioReviewStatus.APPROVED)
            .setVisibility("public").setIsDelete(0).setIndexVersion(1L);

    @BeforeEach
    void setup() throws Exception {
        when(contents.selectById(1L)).thenReturn(content);
        when(audios.selectById(20L)).thenReturn(audio);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        doAnswer(call -> {
            entries.put(call.getArgument(0), call.getArgument(1));
            ttls.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(properties.getLockWaitMillis(), TimeUnit.MILLISECONDS)).thenReturn(true);
    }

    @AfterEach
    void close() {
        metrics.close();
    }

    @Test
    void warmSnapshotUsesOneRedisGetAndNoContentOrAudioQuery() {
        assertEquals(1L, display.readable(1L, 9L).getId());
        clearInvocations(values, redisson, lock);

        Content cached = display.readable(1L, 10L);

        assertEquals(content.getCreateTime(), cached.getCreateTime());
        verify(values).get(key);
        verifyNoMoreInteractions(values);
        verifyNoInteractions(redisson, lock);
        verify(contents).selectById(1L);
        verify(audios).selectById(20L);
        assertTrue(ttls.get(key).toMillis() >= 8000 && ttls.get(key).toMillis() <= 10000);
    }

    @Test
    void cachedOwnerAccessDoesNotAuthorizeOtherViewers() {
        content.setVisibility("private");
        assertEquals(1L, display.readable(1L, 7L).getId());

        assertThrows(BizException.class, () -> display.readable(1L, 9L));
        assertThrows(BizException.class, () -> display.readable(1L, null));
        verify(contents).selectById(1L);
        verify(audios).selectById(20L);
    }

    @Test
    void aDeniedViewerDoesNotCacheTheirDenialForTheOwner() {
        content.setVisibility("private");
        assertThrows(BizException.class, () -> display.readable(1L, 9L));

        assertEquals(1L, display.readable(1L, 7L).getId());
        verify(contents).selectById(1L);
    }

    @Test
    void missingContentUsesTheShortNegativeTtl() {
        when(contents.selectById(1L)).thenReturn(null);
        assertThrows(BizException.class, () -> display.readable(1L, 9L));
        assertThrows(BizException.class, () -> display.readable(1L, 7L));

        verify(contents).selectById(1L);
        verifyNoInteractions(audios, notifications);
        assertEquals("null", entries.get(key));
        assertTrue(ttls.get(key).toMillis() >= 2400 && ttls.get(key).toMillis() <= 3000);
    }

    @ParameterizedTest
    @EnumSource(value = ContentStatusEnum.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void nonPublishedContentIsDeniedAfterCheckingAudio(ContentStatusEnum status) {
        content.setStatus(status);
        assertThrows(BizException.class, () -> display.readable(1L, 7L));
        verify(audios).selectById(20L);
        verifyNoInteractions(notifications);
    }

    @Test
    void deletedContentIsDeniedEvenForItsOwner() {
        content.setIsDelete(1);
        assertThrows(BizException.class, () -> display.readable(1L, 7L));
        verify(audios).selectById(20L);
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @EnumSource(ContentStatusEnum.class)
    void loaderRecordsBannedAudioRegardlessOfContentStatus(ContentStatusEnum status) {
        content.setStatus(status);
        audio.setReviewStatus(AudioReviewStatus.BANNED);
        var loader = new ContentAccessSnapshotLoader(contents, audioAccess);

        ContentAccessSnapshot snapshot = loader.load(1L);

        assertTrue(snapshot.audioBlocked());
        verify(audios).selectById(20L);
    }

    @Test
    void loaderRecordsMissingAudioEvenForDeletedContent() {
        content.setIsDelete(1);
        when(audios.selectById(20L)).thenReturn(null);
        var loader = new ContentAccessSnapshotLoader(contents, audioAccess);

        assertTrue(loader.load(1L).audioBlocked());
        verify(audios).selectById(20L);
    }

    @Test
    void missingAudioBlocksHistoricalReferences() {
        when(audios.selectById(20L)).thenReturn(null);
        assertThrows(BizException.class, () -> display.readable(1L, 7L));
        assertThrows(BizException.class, () -> display.readable(1L, 9L));
        verify(audios).selectById(20L);
        verifyNoInteractions(notifications);
    }

    @Test
    void bannedAudioBlocksAndRequestsTheExistingCascadeOnlyOnLoad() {
        audio.setReviewStatus(AudioReviewStatus.BANNED);
        assertThrows(BizException.class, () -> display.readable(1L, 7L));
        assertThrows(BizException.class, () -> display.readable(1L, 9L));
        verify(audios).selectById(20L);
        verify(notifications).send(anyString(), any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"private,0", "public,1", "private,1"})
    void privateAndSoftDeletedAudioStillAllowHistoricalReferences(String visibility, int deleted) {
        audio.setVisibility(visibility).setIsDelete(deleted);
        assertEquals(1L, display.readable(1L, 9L).getId());
        verifyNoInteractions(notifications);
    }

    @Test
    void anAudioBanIsDetectedWhenTheCombinedSnapshotExpires() {
        display.readable(1L, 9L);
        audio.setReviewStatus(AudioReviewStatus.BANNED);
        assertEquals(1L, display.readable(1L, 9L).getId());

        entries.remove(key); // 模拟固定 TTL 到期；缓存命中不续期。
        assertThrows(BizException.class, () -> display.readable(1L, 9L));
        verify(audios, times(2)).selectById(20L);
        verify(notifications).send(anyString(), any(), anyString());
    }

    @Test
    void contentWithoutAudioDoesNotQueryTheAudioTable() {
        content.setAudioId(null);
        assertEquals(1L, display.readable(1L, 9L).getId());
        verifyNoInteractions(audios, notifications);
    }

    @Test
    void snapshotMissingTheAudioDecisionIsReloadedInsteadOfAuthorizingAccess() {
        audio.setReviewStatus(AudioReviewStatus.BANNED);
        entries.put(key, "{\"content\":" + JsonUtil.toJson(content) + "}");
        assertThrows(BizException.class, () -> display.readable(1L, 9L));
        verify(contents).selectById(1L);
        verify(audios).selectById(20L);
    }

    @Test
    void redisFailureLoadsFromTheSourceWithoutFillingOrTakingALock() {
        when(values.get(key)).thenThrow(new RedisConnectionFailureException("offline"));
        assertEquals(1L, display.readable(1L, 9L).getId());
        assertEquals(1L, display.readable(1L, 10L).getId());
        verify(contents, times(2)).selectById(1L);
        verify(audios, times(2)).selectById(20L);
        verifyNoInteractions(redisson, lock);
        assertTrue(entries.isEmpty());
    }

    @Test
    void liveWriteValidationAndReviewAccessDoNotUseTheDisplaySnapshot() {
        display.readable(1L, 9L);
        content.setStatus(ContentStatusEnum.BANNED);
        assertThrows(BizException.class, () -> liveAccess.readable(1L, 9L));
        assertEquals(ContentStatusEnum.BANNED, display.forReview(1L).getStatus());
        content.setIsDelete(1);
        assertThrows(BizException.class, () -> display.forReview(1L));
        verify(contents, times(4)).selectById(1L);
    }
}

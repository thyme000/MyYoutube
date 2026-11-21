package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader.FillPolicy;
import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;
import cn.diaojiahong.myyoutube.content.domain.ContentUnderstandingResource.Snapshot;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.common.media.ContentArtifacts;
import cn.diaojiahong.myyoutube.common.media.DynamicMedia;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackMetadata;
import cn.diaojiahong.myyoutube.common.media.VideoPlayback;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceRegion;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceWarmupProperties;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentDetailVO;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.impl.ContentFeedServiceImpl;
import cn.diaojiahong.myyoutube.content.strategy.DynamicContentStrategy;
import cn.diaojiahong.myyoutube.content.strategy.VideoContentStrategy;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import cn.diaojiahong.myyoutube.video.service.IAudioService;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import cn.diaojiahong.myyoutube.video.api.dto.VideoPlaybackVO;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceCacheProperties;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.redisson.misc.CompletableFutureWrapper;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentMediaResourcesTest {
    private final ContentMapper contents = mock(ContentMapper.class);
    private final VideoMapper videos = mock(VideoMapper.class);
    private final AudioContentAccess audioAccess = mock(AudioContentAccess.class);
    private final DynamicMapper dynamics = mock(DynamicMapper.class);
    private final ContentUnderstandingMapper understandingRows = mock(ContentUnderstandingMapper.class);
    private final ContentFeedServiceImpl cards = mock(ContentFeedServiceImpl.class);
    private final S3StorageUtil storage = mock(S3StorageUtil.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final Map<String, String> entries = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private final PlaybackTokens tokens = new PlaybackTokens("resource-cache-test-secret-at-least-thirty-two", 21600);
    private final AtomicInteger signatures = new AtomicInteger();
    private final String mediaKey = "media-prepared/9/101/hls_media-720.mp4";
    private final String playlistKey = "media-prepared/9/101/hls_playlist-720.m3u8";
    private final String rawPlaylist = "#EXTM3U\n#EXT-X-MAP:URI=\"hls_media-720.mp4\"\n"
            + "#EXTINF:4.0,\nhls_media-720.mp4\n#EXT-X-ENDLIST\n";
    private final ContentResourceCacheProperties properties = spy(new ContentResourceCacheProperties());
    private Content content;
    private ContentResourceCache cache;
    private ContentMediaResources resources;
    private ContentStreamService streams;
    private ContentReadService reads;
    private ContentUnderstandingResources understanding;

    @BeforeEach
    void setup() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        when(values.multiGet(anyCollection())).thenAnswer(call -> {
            Collection<String> keys = call.getArgument(0);
            return keys.stream().map(entries::get).toList();
        });
        when(redis.delete(anyString())).thenAnswer(call -> entries.remove(call.getArgument(0)) != null);
        doAnswer(call -> {
            entries.put(call.getArgument(0), call.getArgument(1));
            ttls.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        RedisConnection connection = mock(RedisConnection.class);
        RedisStringCommands commands = mock(RedisStringCommands.class);
        when(connection.stringCommands()).thenReturn(commands);
        when(commands.set(any(byte[].class), any(byte[].class), any(Expiration.class), any())).thenAnswer(call -> {
            String key = new String(call.getArgument(0, byte[].class), StandardCharsets.UTF_8);
            entries.put(key, new String(call.getArgument(1, byte[].class), StandardCharsets.UTF_8));
            Expiration expiration = call.getArgument(2);
            ttls.put(key, Duration.ofMillis(expiration.getExpirationTimeInMilliseconds()));
            return true;
        });
        when(redis.executePipelined(any(RedisCallback.class))).thenAnswer(call -> {
            RedisCallback<?> pipeline = call.getArgument(0);
            pipeline.doInRedis(connection);
            return List.of();
        });
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenReturn(true);
        when(lock.tryLockAsync(anyLong(), anyLong(), any(), anyLong())).thenReturn(new CompletableFutureWrapper<>(true));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<Void>(
                CompletableFuture.completedFuture(null)));
        cache = new ContentResourceCache(redis, redisson, properties, new SimpleMeterRegistry());
        var registry = new ContentTypeStrategyRegistry(List.of(new VideoContentStrategy(videos),
                new DynamicContentStrategy(dynamics, mock(IAudioService.class))));
        resources = new ContentMediaResources(new ContentMediaResourceLoader(registry), storage);
        var access = new ContentDisplayAccess(cache, new ContentAccessSnapshotLoader(contents, audioAccess),
                new ContentAccessService(contents, audioAccess, org.mockito.Mockito.mock(UserBlockPort.class)));
        streams = new ContentStreamService(cache, access, resources, storage, tokens, properties);
        understanding = new ContentUnderstandingResources(new ContentUnderstandingResourceLoader(understandingRows));
        reads = new ContentReadService(cache, access, resources, cards, understanding, storage, streams, properties);

        content = new Content().setId(1L).setType(ContentTypeEnum.VIDEO).setAuthorId(9L)
                .setStatus(ContentStatusEnum.PUBLISHED).setIsDelete(0).setVisibility("public").setPlayCount(100L);
        when(contents.selectById(1L)).thenReturn(content);
        VideoPlayback playback = new VideoPlayback(1280, 720, List.of(new VideoPlayback.Rendition(
                720, mediaKey, playlistKey, 1280, 720, 3000000,
                new PlaybackMetadata("avc1.64001f,mp4a.40.2", 3000000, 25))));
        when(videos.selectById(1L)).thenReturn(new Video().setId(1L).setObjectKey("original.mp4")
                .setDurationMs(12000L).setPlaybackJson(JsonUtil.toJson(playback)));
        ContentFeedVO card = new ContentFeedVO();
        card.setId("1");
        when(cards.assembleCardsWithMedia(anyLong(), anyList(), anySet(), anyMap())).thenReturn(List.of(card));
        when(storage.readObject(eq(playlistKey), any(Duration.class)))
                .thenReturn(rawPlaylist.getBytes(StandardCharsets.UTF_8));
        when(storage.presignReadUrl(anyString(), anyLong(), any(SdkHttpMethod.class)))
                .thenAnswer(call -> "https://signed.example/" + call.getArgument(0) + "?signature=" + signatures.incrementAndGet());
        when(storage.signGetUrl(anyString(), anyLong()))
                .thenAnswer(call -> "https://signed.example/" + call.getArgument(0) + "?signature=" + signatures.incrementAndGet());
    }

    @Test
    void authorWarmupUsesStandardTtlForNewContentAndTheNextViewerReusesIt() {
        content.setPlayCount(0L);
        String subtitleKey = "subtitles/warmed.vtt";
        ContentArtifacts.Chapter chapter = new ContentArtifacts.Chapter(100, "预热章节");
        Snapshot snapshot = new Snapshot(true, "预热摘要", JsonUtil.toJson(List.of(chapter)), subtitleKey);
        when(storage.readObject(eq(subtitleKey), any(Duration.class))).thenReturn("WEBVTT".getBytes(StandardCharsets.UTF_8));
        ContentResourceWarmupLoader eligible = mock(ContentResourceWarmupLoader.class);
        when(eligible.loadReadable(1L)).thenReturn(content);
        when(eligible.aboveFanThreshold(content)).thenReturn(true);
        var warmMetrics = new SimpleMeterRegistry();
        try {
            var warmup = new ContentResourceWarmup(Runnable::run, Runnable::run, new ContentResourceWarmupProperties(), eligible,
                    resources, understanding, cache, warmMetrics);

            warmup.submit(1L, snapshot);

            assertEquals(Set.of(ContentResourceRegion.CONTENT_MEDIA.key("1"), ContentResourceRegion.UNDERSTANDING.key("1"),
                    ContentResourceRegion.PLAYLIST.key(playlistKey), ContentResourceRegion.SUBTITLE.key(subtitleKey)),
                    entries.keySet());
            entries.keySet().forEach(key -> assertTtl(key, 1440000, 1800000));
            assertEquals(JsonUtil.toJson(rawPlaylist), entries.get(ContentResourceRegion.PLAYLIST.key(playlistKey)));
            assertEquals(JsonUtil.toJson("WEBVTT"), entries.get(ContentResourceRegion.SUBTITLE.key(subtitleKey)));
            verifyNoInteractions(values);
            verify(redis, times(2)).executePipelined(any(RedisCallback.class));
            verify(values, never()).set(anyString(), anyString(), any(Duration.class));
            assertTrue(entries.values().stream().noneMatch(payload -> payload.contains("playbackToken")));
            verify(storage).readObject(playlistKey, Duration.ofSeconds(10));
            verify(storage).readObject(subtitleKey, Duration.ofSeconds(10));
            verifyNoMoreInteractions(storage);

            ContentDetailVO detail = reads.detail(1L, 9L);
            assertEquals("预热摘要", detail.getSummary());
            assertEquals(List.of(chapter), detail.getChapters());
            assertTrue(streams.playlist(1L, 720, 9L, ticket(9)).contains("https://signed.example/"));
            assertEquals("WEBVTT", reads.subtitles(1L, 9L));
            verify(storage, times(2)).readObject(anyString(), any(Duration.class));
            verify(videos).selectById(1L);
            verifyNoInteractions(understandingRows);
        } finally {
            warmMetrics.close();
        }
    }

    @Test
    void authorWarmupRefreshesExistingShortEntriesWithStandardTtl() {
        content.setPlayCount(0L);
        when(understandingRows.selectById(1L)).thenReturn(new ContentUnderstanding().setReceived(true).setSummary("摘要"));
        reads.detail(1L, 9L);
        Duration mediaTtl = ttls.get(ContentResourceRegion.CONTENT_MEDIA.key("1"));
        Duration understandingTtl = ttls.get(ContentResourceRegion.UNDERSTANDING.key("1"));
        clearInvocations(values, videos, understandingRows, redis);
        ContentResourceWarmupLoader eligible = mock(ContentResourceWarmupLoader.class);
        when(eligible.loadReadable(1L)).thenReturn(content);
        when(eligible.aboveFanThreshold(content)).thenReturn(true);
        var warmMetrics = new SimpleMeterRegistry();
        try {
            var warmup = new ContentResourceWarmup(Runnable::run, Runnable::run, new ContentResourceWarmupProperties(), eligible,
                    resources, understanding, cache, warmMetrics);

            warmup.submit(1L, new Snapshot(true, "摘要", null, null));

            assertTtl(ContentResourceRegion.CONTENT_MEDIA.key("1"), 1440000, 1800000);
            assertTtl(ContentResourceRegion.UNDERSTANDING.key("1"), 1440000, 1800000);
            assertTrue(ttls.get(ContentResourceRegion.CONTENT_MEDIA.key("1")).compareTo(mediaTtl) > 0);
            assertTrue(ttls.get(ContentResourceRegion.UNDERSTANDING.key("1")).compareTo(understandingTtl) > 0);
            verify(values, never()).set(anyString(), anyString(), any(Duration.class));
            verify(redis, never()).delete(anyString());
            verify(videos).selectById(1L);
            verifyNoInteractions(understandingRows);
            verifyNoInteractions(values);
        } finally {
            warmMetrics.close();
        }
    }

    @Test
    void cachedStateDelaysTheLongTtlUntilItIsReloadedAfterPlayCountFlush() {
        content.setPlayCount(99L);
        when(understandingRows.selectById(1L)).thenReturn(new ContentUnderstanding().setReceived(true).setSummary("摘要"));

        assertEquals("摘要", reads.detail(1L, 9L).getSummary());
        content.setPlayCount(100L);
        assertEquals("摘要", reads.detail(1L, 10L).getSummary());
        assertTtl(ContentResourceRegion.CONTENT_MEDIA.key("1"), 48000, 60000);
        verify(properties, times(2)).artifactFillPolicy(99L);
        verify(contents).selectById(1L);

        // 模拟状态及短期产物到期；重新读取累计播放量后，下一次填充使用常规 TTL。
        entries.clear();
        reads.detail(1L, 9L);
        reads.detail(1L, 10L);

        assertTtl(ContentResourceRegion.CONTENT_MEDIA.key("1"), 1440000, 1800000);
        assertTtl(ContentResourceRegion.UNDERSTANDING.key("1"), 1440000, 1800000);
        verify(properties, times(2)).artifactFillPolicy(100L);
        verify(contents, times(2)).selectById(1L);
        verify(videos, times(2)).selectById(1L);
        verify(understandingRows, times(2)).selectById(1L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, 99})
    void lowPlayDetailSharesShortLivedArtifactsAndSelectsPolicyOncePerRequest(Long playCount) {
        content.setPlayCount(playCount);
        when(understandingRows.selectById(1L)).thenReturn(new ContentUnderstanding().setReceived(true).setSummary("摘要"));

        assertEquals("摘要", reads.detail(1L, 9L).getSummary());
        assertEquals("摘要", reads.detail(1L, 10L).getSummary());

        assertEquals(Set.of(ContentResourceRegion.CONTENT_STATE.key("1"), ContentResourceRegion.CONTENT_MEDIA.key("1"),
                ContentResourceRegion.UNDERSTANDING.key("1")), entries.keySet());
        assertTtl(ContentResourceRegion.CONTENT_STATE.key("1"), 8000, 10000);
        assertTtl(ContentResourceRegion.CONTENT_MEDIA.key("1"), 48000, 60000);
        assertTtl(ContentResourceRegion.UNDERSTANDING.key("1"), 48000, 60000);
        verify(contents).selectById(1L);
        verify(videos).selectById(1L);
        verify(understandingRows).selectById(1L);
        verify(properties, times(2)).artifactFillPolicy(playCount);
    }

    @Test
    void lowPlayRenditionsShareShortLivedMediaAndTextButSignEveryRequest() {
        content.setPlayCount(0L);

        String first = streams.playlist(1L, 720, 9L, ticket(9));
        String second = streams.playlist(1L, 720, 10L, ticket(10));

        assertNotEquals(first, second);
        assertEquals(Set.of(ContentResourceRegion.CONTENT_STATE.key("1"), ContentResourceRegion.CONTENT_MEDIA.key("1"),
                ContentResourceRegion.PLAYLIST.key(playlistKey)), entries.keySet());
        assertTtl(ContentResourceRegion.CONTENT_MEDIA.key("1"), 48000, 60000);
        assertTtl(ContentResourceRegion.PLAYLIST.key(playlistKey), 48000, 60000);
        verify(videos).selectById(1L);
        verify(storage).readObject(playlistKey, Duration.ofSeconds(10));
        verify(properties, times(2)).artifactFillPolicy(0L);
    }

    @Test
    void lowPlaySubtitlesUseOnePolicyForPointerAndText() {
        content.setPlayCount(0L);
        String subtitleKey = "subtitles/short.vtt";
        when(understandingRows.selectById(1L)).thenReturn(new ContentUnderstanding().setReceived(true).setSubtitleKey(subtitleKey));
        when(storage.readObject(subtitleKey, Duration.ofSeconds(10))).thenReturn("WEBVTT".getBytes(StandardCharsets.UTF_8));

        assertEquals("WEBVTT", reads.subtitles(1L, 9L));
        assertEquals("WEBVTT", reads.subtitles(1L, 10L));

        assertEquals(Set.of(ContentResourceRegion.CONTENT_STATE.key("1"), ContentResourceRegion.UNDERSTANDING.key("1"),
                ContentResourceRegion.SUBTITLE.key(subtitleKey)), entries.keySet());
        assertTtl(ContentResourceRegion.UNDERSTANDING.key("1"), 48000, 60000);
        assertTtl(ContentResourceRegion.SUBTITLE.key(subtitleKey), 48000, 60000);
        verify(understandingRows).selectById(1L);
        verify(storage).readObject(subtitleKey, Duration.ofSeconds(10));
        verify(properties, times(2)).artifactFillPolicy(0L);
    }

    @Test
    void dynamicContentUsesShortMediaTtlAndUnchangedNegativeTtl() {
        dynamic();
        content.setPlayCount(0L);

        assertNotNull(reads.detail(1L, 9L).getMedia().dynamic());
        assertNotNull(reads.detail(1L, 10L).getMedia().dynamic());

        assertEquals(Set.of(ContentResourceRegion.CONTENT_STATE.key("1"), ContentResourceRegion.CONTENT_MEDIA.key("1"),
                ContentResourceRegion.UNDERSTANDING.key("1")), entries.keySet());
        assertEquals("null", entries.get(ContentResourceRegion.UNDERSTANDING.key("1")));
        assertTtl(ContentResourceRegion.CONTENT_MEDIA.key("1"), 48000, 60000);
        assertTtl(ContentResourceRegion.UNDERSTANDING.key("1"), 2400, 3000);
        verify(dynamics).selectBatchIds(List.of(1L));
    }

    @Test
    void increasingThresholdKeepsExistingArtifactsUsableUntilTheirTtlExpires() {
        reads.detail(1L, 9L);
        properties.setArtifactMinPlayCount(1000);
        clearInvocations(contents, videos, understandingRows, redisson);

        assertNotNull(reads.detail(1L, 10L).getMedia().video());

        verifyNoInteractions(contents, videos, understandingRows, redisson);
    }

    @Test
    void reviewResourcesUseShortTtlAndKeepAccessChecksInTheDatabase() {
        content.setStatus(ContentStatusEnum.REVIEW).setPlayCount(0L);

        assertNotNull(reads.adminMedia(1L, 9L).video());
        assertNotNull(reads.adminMedia(1L, 9L).video());

        assertEquals(Set.of(ContentResourceRegion.CONTENT_MEDIA.key("1")), entries.keySet());
        assertTtl(ContentResourceRegion.CONTENT_MEDIA.key("1"), 48000, 60000);
        verify(contents, times(2)).selectById(1L);
        verify(videos).selectById(1L);
        verify(properties, times(2)).artifactFillPolicy(0L);
    }

    @Test
    void detailReusesOneCachedMediaSnapshotAndIssuesASeparateGrantForEachViewer() {
        ContentDetailVO first = reads.detail(1L, 9L);
        ContentDetailVO second = reads.detail(1L, 10L);

        assertEquals(9, grant(first.getMedia().video().playback()).userId());
        assertEquals(10, grant(second.getMedia().video().playback()).userId());
        assertEquals(first.getMedia().video().playback().playbackVersion(),
                second.getMedia().video().playback().playbackVersion());
        verify(videos).selectById(1L);
        verify(contents).selectById(1L);
        verify(audioAccess).isReferenceBlocked(content);
        verify(cards, never()).assembleCards(anyLong(), anyList(), anySet());
        verify(values).get(ContentResourceRegion.CONTENT_MEDIA.key("1"));
        verify(values, times(2)).multiGet(List.of(ContentResourceRegion.CONTENT_STATE.key("1"),
                ContentResourceRegion.CONTENT_MEDIA.key("1"), ContentResourceRegion.UNDERSTANDING.key("1")));
        assertEquals(Set.of(ContentResourceRegion.CONTENT_STATE.key("1"),
                ContentResourceRegion.CONTENT_MEDIA.key("1"), ContentResourceRegion.UNDERSTANDING.key("1")), entries.keySet());
        assertEquals("null", entries.get(ContentResourceRegion.UNDERSTANDING.key("1")));
        assertTrue(entries.values().stream().noneMatch(payload -> payload.contains("playbackToken")));
        verifyNoInteractions(storage);
    }

    @Test
    void warmDetailUsesOneMultiGetAndNoIndividualCacheReads() {
        reads.detail(1L, 9L);
        clearInvocations(values, contents, videos, understandingRows, redisson);

        reads.detail(1L, 10L);

        verify(values).multiGet(List.of(ContentResourceRegion.CONTENT_STATE.key("1"),
                ContentResourceRegion.CONTENT_MEDIA.key("1"), ContentResourceRegion.UNDERSTANDING.key("1")));
        verify(values, never()).get(anyString());
        verifyNoInteractions(contents, videos, understandingRows, redisson);
    }

    @Test
    void warmPlaybackAndMasterEachUseOneMultiGet() {
        streams.playback(1L, 9L);
        clearInvocations(values, contents, videos, redisson);

        streams.playback(1L, 10L);
        streams.masterPlaylist(1L, 10L, ticket(10));

        verify(values, times(2)).multiGet(List.of(ContentResourceRegion.CONTENT_STATE.key("1"),
                ContentResourceRegion.CONTENT_MEDIA.key("1")));
        verify(values, never()).get(anyString());
        verifyNoInteractions(contents, videos, redisson);
    }

    @Test
    void warmRenditionReadsTheDependentPlaylistAfterOneMultiGet() {
        streams.playlist(1L, 720, 9L, ticket(9));
        clearInvocations(values, contents, videos, storage, redisson);

        streams.playlist(1L, 720, 10L, ticket(10));

        verify(values).multiGet(List.of(ContentResourceRegion.CONTENT_STATE.key("1"),
                ContentResourceRegion.CONTENT_MEDIA.key("1")));
        verify(values).get(ContentResourceRegion.PLAYLIST.key(playlistKey));
        verify(values, times(1)).get(anyString());
        verifyNoInteractions(contents, videos, redisson);
        verify(storage, never()).readObject(anyString(), any(Duration.class));
    }

    @Test
    void warmSubtitlesReadTheDependentTextAfterOneMultiGet() {
        String subtitleKey = "subtitles/version-one.vtt";
        when(understandingRows.selectById(1L)).thenReturn(new ContentUnderstanding().setReceived(true).setSubtitleKey(subtitleKey));
        when(storage.readObject(eq(subtitleKey), any(Duration.class))).thenReturn("WEBVTT".getBytes(StandardCharsets.UTF_8));
        reads.subtitles(1L, 9L);
        clearInvocations(values, contents, understandingRows, storage, redisson);

        assertEquals("WEBVTT", reads.subtitles(1L, 10L));

        verify(values).multiGet(List.of(ContentResourceRegion.CONTENT_STATE.key("1"), ContentResourceRegion.UNDERSTANDING.key("1")));
        verify(values).get(ContentResourceRegion.SUBTITLE.key(subtitleKey));
        verify(values, times(1)).get(anyString());
        verifyNoInteractions(contents, understandingRows, storage, redisson);
    }

    @Test
    void deniedAccessDoesNotDecodeOrLoadPrefetchedArtifacts() {
        content.setStatus(ContentStatusEnum.BANNED);
        entries.put(ContentResourceRegion.CONTENT_MEDIA.key("1"), "invalid-json");
        entries.put(ContentResourceRegion.UNDERSTANDING.key("1"), "invalid-json");

        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, () -> reads.detail(1L, 9L)).getErrorCode());

        verifyNoInteractions(videos, understandingRows, cards, storage);
        verify(values, never()).get(ContentResourceRegion.CONTENT_MEDIA.key("1"));
        verify(values, never()).get(ContentResourceRegion.UNDERSTANDING.key("1"));
    }

    @Test
    void failedMultiGetStillReturnsAuthorizedDetailWithoutFillingCaches() {
        when(values.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("unavailable"));

        assertEquals(9, grant(reads.detail(1L, 9L).getMedia().video().playback()).userId());

        verify(contents).selectById(1L);
        verify(videos).selectById(1L);
        verify(understandingRows).selectById(1L);
        verify(values, never()).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(redisson);
        assertTrue(entries.isEmpty());
    }

    @Test
    void playlistsCacheOnlyTheRawTextAndSignAgainForEveryRequest() {
        String first = streams.playlist(1L, 720, 9L, ticket(9));
        String second = streams.playlist(1L, 720, 10L, ticket(10));

        assertNotEquals(first, second);
        assertTrue(first.contains("signature=1"));
        assertTrue(second.contains("signature=2"));
        verify(videos).selectById(1L);
        verify(storage).readObject(playlistKey, Duration.ofSeconds(10));
        verify(storage, times(2)).presignReadUrl(mediaKey, 1200, SdkHttpMethod.GET);
        assertEquals(rawPlaylist, JsonUtil.fromJson(entries.get(ContentResourceRegion.PLAYLIST.key(playlistKey)), String.class));
    }

    @Test
    void completedUnderstandingIsSharedWithoutSharingSignaturesOrAuthorization() {
        ContentArtifacts.Chapter chapter = new ContentArtifacts.Chapter(1000, "第一章");
        when(understandingRows.selectById(1L)).thenReturn(new ContentUnderstanding().setReceived(true)
                .setSummary("摘要").setChaptersJson(JsonUtil.toJson(List.of(chapter))).setSubtitleKey("subtitles/one.vtt"));

        ContentDetailVO first = reads.detail(1L, 9L);
        ContentDetailVO second = reads.detail(1L, 10L);

        assertEquals("摘要", second.getSummary());
        assertEquals(List.of(chapter), second.getChapters());
        assertNotEquals(first.getSubtitleUrl(), second.getSubtitleUrl());
        verify(understandingRows).selectById(1L);
        verify(storage, times(2)).signGetUrl("subtitles/one.vtt", 1200);
        assertFalse(entries.get(ContentResourceRegion.UNDERSTANDING.key("1")).contains("signature="));

        content.setStatus(ContentStatusEnum.BANNED);
        cache.evict(ContentResourceRegion.CONTENT_STATE, "1");
        assertThrows(BizException.class, () -> reads.detail(1L, 9L));
        verify(understandingRows).selectById(1L);
        verify(storage, times(2)).signGetUrl("subtitles/one.vtt", 1200);
    }

    @Test
    void warmPlaylistAndMediaDoNotBypassContentBansOrVisibilityChanges() {
        streams.playlist(1L, 720, 10L, ticket(10));

        content.setStatus(ContentStatusEnum.BANNED);
        cache.evict(ContentResourceRegion.CONTENT_STATE, "1");
        assertThrows(BizException.class, () -> streams.playlist(1L, 720, 10L, ticket(10)));
        content.setStatus(ContentStatusEnum.PUBLISHED).setVisibility("private");
        cache.evict(ContentResourceRegion.CONTENT_STATE, "1");
        assertThrows(BizException.class, () -> streams.playlist(1L, 720, 10L, ticket(10)));

        verify(storage).presignReadUrl(mediaKey, 1200, SdkHttpMethod.GET);
        verify(videos).selectById(1L);
    }

    @Test
    void dynamicImagesAndAudioShareOneRawSnapshotAndGetNewSignedUrls() {
        ContentMedia media = dynamic();
        ContentDetailVO first = reads.detail(1L, 9L);
        ContentDetailVO second = reads.detail(1L, 10L);

        assertNotEquals(first.getMedia().dynamic().items().get(0).url(), second.getMedia().dynamic().items().get(0).url());
        assertNotEquals(first.getMedia().dynamic().audio().url(), second.getMedia().dynamic().audio().url());
        verify(dynamics).selectBatchIds(List.of(1L));
        verifyNoInteractions(videos);
        assertEquals(Set.of(ContentResourceRegion.CONTENT_STATE.key("1"),
                ContentResourceRegion.CONTENT_MEDIA.key("1"), ContentResourceRegion.UNDERSTANDING.key("1")), entries.keySet());
        assertEquals("null", entries.get(ContentResourceRegion.UNDERSTANDING.key("1")));
        String payload = entries.get(ContentResourceRegion.CONTENT_MEDIA.key("1"));
        assertTrue(payload.contains("image-one"));
        assertTrue(payload.contains("music.mp3"));
        assertFalse(payload.contains("signed.example"));
        assertEquals(media, resources.media(content, cache, FillPolicy.STANDARD).media());
    }

    @Test
    void warmDynamicResourcesRecheckAudioWhenTheStateSnapshotExpires() {
        dynamic();
        reads.detail(1L, 9L);
        int signedBeforeBan = signatures.get();
        when(audioAccess.isReferenceBlocked(content)).thenReturn(true);
        entries.remove(ContentResourceRegion.CONTENT_STATE.key("1"));

        assertThrows(BizException.class, () -> reads.detail(1L, 9L));

        assertEquals(signedBeforeBan, signatures.get());
        verify(dynamics).selectBatchIds(List.of(1L));
    }

    @Test
    void subtitleTextIsCachedByItsObjectKeyAndRegeneratedKeysGetFreshText() {
        ContentUnderstanding row = new ContentUnderstanding().setReceived(true).setSubtitleKey("subtitles/version-one.vtt");
        when(understandingRows.selectById(1L)).thenReturn(row);
        when(storage.readObject(eq("subtitles/version-one.vtt"), any(Duration.class)))
                .thenReturn("WEBVTT\nfirst".getBytes(StandardCharsets.UTF_8));
        when(storage.readObject(eq("subtitles/version-two.vtt"), any(Duration.class)))
                .thenReturn("WEBVTT\nsecond".getBytes(StandardCharsets.UTF_8));

        assertEquals("WEBVTT\nfirst", reads.subtitles(1L, 9L));
        assertEquals("WEBVTT\nfirst", reads.subtitles(1L, 10L));
        row.setSubtitleKey("subtitles/version-two.vtt");
        cache.evict(ContentResourceRegion.UNDERSTANDING, "1");
        assertEquals("WEBVTT\nsecond", reads.subtitles(1L, 9L));

        verify(storage).readObject("subtitles/version-one.vtt", Duration.ofSeconds(10));
        verify(storage).readObject("subtitles/version-two.vtt", Duration.ofSeconds(10));
        verify(understandingRows, times(2)).selectById(1L);
        content.setIsDelete(1);
        cache.evict(ContentResourceRegion.CONTENT_STATE, "1");
        assertThrows(BizException.class, () -> reads.subtitles(1L, 9L));
        verify(contents, times(2)).selectById(1L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void absentSubtitleKeysAreRejectedBeforeReadingTheSubtitleCacheOrStorage(String key) {
        when(understandingRows.selectById(1L)).thenReturn(new ContentUnderstanding().setReceived(true).setSubtitleKey(key));

        BizException failure = assertThrows(BizException.class, () -> reads.subtitles(1L, 9L));

        assertEquals(ErrorCode.NOT_FOUND, failure.getErrorCode());
        verifyNoInteractions(storage);
        assertEquals(Set.of(ContentResourceRegion.CONTENT_STATE.key("1"),
                ContentResourceRegion.UNDERSTANDING.key("1")), entries.keySet());
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void storageFailuresAreTranslatedAndNeverWrittenAsNegativeCache(SdkException source, ErrorCode expected) {
        when(storage.readObject(eq(playlistKey), any(Duration.class))).thenThrow(source);

        DependencyException failure = assertThrows(DependencyException.class,
                () -> streams.playlist(1L, 720, 9L, ticket(9)));

        assertEquals(expected, failure.getErrorCode());
        assertSame(source, failure.getCause());
        assertFalse(entries.containsKey(ContentResourceRegion.PLAYLIST.key(playlistKey)));
    }

    private static Stream<Arguments> storageFailures() {
        return Stream.of(
                Arguments.of(ApiCallTimeoutException.create(10000), ErrorCode.DEPENDENCY_TIMEOUT),
                Arguments.of(ApiCallAttemptTimeoutException.create(10000), ErrorCode.DEPENDENCY_TIMEOUT),
                Arguments.of(S3Exception.builder().statusCode(429).build(), ErrorCode.DEPENDENCY_UNAVAILABLE),
                Arguments.of(S3Exception.builder().statusCode(503).build(), ErrorCode.DEPENDENCY_UNAVAILABLE),
                Arguments.of(S3Exception.builder().statusCode(504).build(), ErrorCode.DEPENDENCY_TIMEOUT),
                Arguments.of(S3Exception.builder().statusCode(500).build(), ErrorCode.DEPENDENCY_FAILURE),
                Arguments.of(NoSuchKeyException.builder().statusCode(404).build(), ErrorCode.DEPENDENCY_FAILURE),
                Arguments.of(SdkClientException.create("connection failure"), ErrorCode.DEPENDENCY_FAILURE));
    }

    @Test
    void reviewResourceCacheDoesNotAuthorizeOrdinaryPlayback() {
        content.setStatus(ContentStatusEnum.REVIEW).setVisibility("private");
        VideoPlaybackVO review = reads.adminMedia(1L, 9L).video().playback();
        assertEquals(PlaybackTokens.Scope.REVIEW, grant(review).scope());

        assertThrows(BizException.class, () -> reads.detail(1L, 9L));
        assertThrows(BizException.class, () -> streams.playlist(1L, 720, 9L,
                review.adaptivePlaylistUrl().split("playbackToken=")[1]));
        verify(videos).selectById(1L);
        verifyNoInteractions(storage);
    }

    @Test
    void missingMediaUsesANegativeCacheWithoutHidingTheNotFoundResult() {
        when(videos.selectById(1L)).thenReturn(null);

        for (int request = 0; request < 2; request++) {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, () -> resources.media(content, cache, FillPolicy.STANDARD)).getErrorCode());
        }

        verify(videos).selectById(1L);
        assertEquals("null", entries.get(ContentResourceRegion.CONTENT_MEDIA.key("1")));
    }

    @Test
    void incompleteCachedVideoDescriptorIsRebuiltInsteadOfReturningBrokenPlaybackData() {
        entries.put(ContentResourceRegion.CONTENT_MEDIA.key("1"),
                "{\"media\":{\"type\":\"video\",\"video\":{\"objectKey\":\"original.mp4\",\"durationMs\":\"12000\"}}}");

        assertNotNull(streams.playback(1L, 9L).playbackVersion());

        verify(videos).selectById(1L);
        assertNotNull(JsonUtil.fromJson(entries.get(ContentResourceRegion.CONTENT_MEDIA.key("1")),
                ContentMediaResource.class).playback());
    }

    private ContentMedia dynamic() {
        content.setType(ContentTypeEnum.DYNAMIC).setAudioId(20L);
        DynamicMedia dynamic = new DynamicMedia("vertical", 1080, 1920, "cut", 0,
                List.of(new DynamicMedia.Image("image-one", 0, 3000, .5, .5),
                        new DynamicMedia.Image("image-two", 3000, 3000, .5, .5)),
                new DynamicMedia.Audio(20L, "music.mp3", "music", 120000), 6000);
        ContentMedia media = new ContentMedia("dynamic", null, dynamic);
        when(dynamics.selectBatchIds(List.of(1L)))
                .thenReturn(List.of(new Dynamic().setId(1L).setMediaJson(JsonUtil.toJson(dynamic))));
        return media;
    }

    private String ticket(long viewer) {
        return tokens.issue(viewer, 1, 12000, PlaybackTokens.Scope.VIEW).token();
    }

    private PlaybackTokens.Grant grant(VideoPlaybackVO playback) {
        return tokens.verify(playback.adaptivePlaylistUrl().split("playbackToken=")[1]);
    }
    private void assertTtl(String key, long minimumMillis, long maximumMillis) {
        long ttl = ttls.get(key).toMillis();
        assertTrue(ttl >= minimumMillis && ttl <= maximumMillis, key + " ttl=" + ttl);
    }

}

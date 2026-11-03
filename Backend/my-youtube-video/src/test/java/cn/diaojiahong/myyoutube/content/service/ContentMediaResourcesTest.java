package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;

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
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final PlaybackTokens tokens = new PlaybackTokens("resource-cache-test-secret-at-least-thirty-two", 21600);
    private final AtomicInteger signatures = new AtomicInteger();
    private final String mediaKey = "media-prepared/9/101/hls_media-720.mp4";
    private final String playlistKey = "media-prepared/9/101/hls_playlist-720.m3u8";
    private final String rawPlaylist = "#EXTM3U\n#EXT-X-MAP:URI=\"hls_media-720.mp4\"\n"
            + "#EXTINF:4.0,\nhls_media-720.mp4\n#EXT-X-ENDLIST\n";
    private Content content;
    private ContentResourceCache cache;
    private ContentMediaResources resources;
    private ContentStreamService streams;
    private ContentReadService reads;

    @BeforeEach
    void setup() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(call -> entries.remove(call.getArgument(0)) != null);
        doAnswer(call -> {
            entries.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenReturn(true);
        cache = new ContentResourceCache(redis, redisson, new ContentResourceCacheProperties(), new SimpleMeterRegistry());
        var registry = new ContentTypeStrategyRegistry(List.of(new VideoContentStrategy(videos),
                new DynamicContentStrategy(dynamics, mock(IAudioService.class))));
        resources = new ContentMediaResources(cache, new ContentMediaResourceLoader(registry), storage);
        var access = new ContentDisplayAccess(cache, new ContentAccessSnapshotLoader(contents, audioAccess),
                new ContentAccessService(contents, audioAccess));
        streams = new ContentStreamService(access, resources, storage, tokens);
        var understanding = new ContentUnderstandingResources(cache, new ContentUnderstandingResourceLoader(understandingRows));
        reads = new ContentReadService(access, resources, cards, understanding, storage, streams);

        content = new Content().setId(1L).setType(ContentTypeEnum.VIDEO).setAuthorId(9L)
                .setStatus(ContentStatusEnum.PUBLISHED).setIsDelete(0).setVisibility("public");
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
        verify(values, times(3)).get(ContentResourceRegion.CONTENT_MEDIA.key("1"));
        assertEquals(Set.of(ContentResourceRegion.CONTENT_STATE.key("1"),
                ContentResourceRegion.CONTENT_MEDIA.key("1"), ContentResourceRegion.UNDERSTANDING.key("1")), entries.keySet());
        assertEquals("null", entries.get(ContentResourceRegion.UNDERSTANDING.key("1")));
        assertTrue(entries.values().stream().noneMatch(payload -> payload.contains("playbackToken")));
        verifyNoInteractions(storage);
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
        assertEquals(media, resources.media(content).media());
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
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, () -> resources.media(content)).getErrorCode());
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
}

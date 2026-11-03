package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackMetadata;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.media.VideoPlayback;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.controller.ContentStreamController;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.AudioContentAccess;
import cn.diaojiahong.myyoutube.content.service.ContentStreamService;
import cn.diaojiahong.myyoutube.content.service.ContentMediaResources;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.content.strategy.VideoContentStrategy;
import cn.diaojiahong.myyoutube.content.support.ContentResourceTestSupport;
import java.time.Duration;
import cn.diaojiahong.myyoutube.video.api.dto.VideoPlaybackVO;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.http.SdkHttpMethod;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContentStreamTest {
    private final ContentMapper contents = mock(ContentMapper.class);
    private final VideoMapper videos = mock(VideoMapper.class);
    private final AudioContentAccess audio = mock(AudioContentAccess.class);
    private final S3StorageUtil storage = mock(S3StorageUtil.class);
    private final PlaybackTokens tokens = new PlaybackTokens("test-secret-at-least-thirty-two-characters", 21600);
    private final String TOKEN = tokens.issue(9, 1, 12000, PlaybackTokens.Scope.VIEW).token();
    private final ContentMediaResources resources = ContentResourceTestSupport.uncached(
            new ContentTypeStrategyRegistry(List.of(new VideoContentStrategy(videos))), storage);
    private final ContentStreamService streams = new ContentStreamService(ContentResourceTestSupport.uncachedAccess(contents, audio), resources, storage, tokens);
    private final String originalKey = "media-prepared/9/101/video-original";
    private final String mediaKey = "media-prepared/9/101/hls_media-720.mp4";
    private final String playlistKey = "media-prepared/9/101/hls_playlist-720.m3u8";
    private final String sourceMediaKey = "media-prepared/9/101/hls_media-0.mp4";
    private final String sourcePlaylistKey = "media-prepared/9/101/hls_playlist-0.m3u8";
    private Content content;
    private Video video;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        UserContext.setUser("9");
        content = new Content().setId(1L).setType(ContentTypeEnum.VIDEO).setAuthorId(9L)
                .setStatus(ContentStatusEnum.PUBLISHED).setIsDelete(0).setVisibility("public");
        when(contents.selectById(1L)).thenReturn(content);
        video = new Video().setId(1L).setObjectKey(originalKey).setDurationMs(12000L)
                .setPlaybackJson(JsonUtil.toJson(new VideoPlayback(1920, 1080,
                        List.of(new VideoPlayback.Rendition(720, mediaKey, playlistKey, 1280, 720, 3000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 3000000, 25)),
                                new VideoPlayback.Rendition(0, sourceMediaKey, sourcePlaylistKey, 1920, 1080, 5000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 5000000, 25))))));
        when(videos.selectById(1L)).thenReturn(video);
        when(storage.presignReadUrl(anyString(), anyLong(), any(SdkHttpMethod.class)))
                .thenAnswer(call -> "https://storage.example/" + call.getArgument(0) + "?signature=test");
        when(storage.readObject(eq(playlistKey), any(Duration.class))).thenReturn(("#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"hls_media-720.mp4\",BYTERANGE=\"800@0\"\n"
                + "#EXTINF:4.0,\n#EXT-X-BYTERANGE:2000@800\nhls_media-720.mp4\n#EXT-X-ENDLIST\n")
                .getBytes(StandardCharsets.UTF_8));
        when(storage.readObject(eq(sourcePlaylistKey), any(Duration.class))).thenReturn(("#EXTM3U\n"
                + "#EXT-X-MAP:URI=\"hls_media-0.mp4\",BYTERANGE=\"800@0\"\n"
                + "#EXTINF:4.0,\n#EXT-X-BYTERANGE:2000@800\nhls_media-0.mp4\n#EXT-X-ENDLIST\n")
                .getBytes(StandardCharsets.UTF_8));
        mvc = MockMvcBuilders.standaloneSetup(new ContentStreamController(streams))
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").header(PlaybackTokens.INTERNAL_HEADER, TOKEN)).build();
    }

    @AfterEach
    void clear() { UserContext.removeUser(); }

    @Test
    void reviewTicketReadsUnpublishedVideoButCannotBeSubstitutedWithAViewerTicket() {
        content.setStatus(ContentStatusEnum.REVIEW).setVisibility("private");
        VideoPlaybackVO playback = streams.reviewPlayback(1L, 10L);
        assertTrue(playback.adaptivePlaylistUrl().startsWith("/admin/content/1/streams/master.m3u8?playbackToken="));
        String token = playback.adaptivePlaylistUrl().split("playbackToken=")[1];
        assertEquals(PlaybackTokens.Scope.REVIEW, tokens.verify(token).scope());
        assertTrue(streams.reviewMasterPlaylist(1L, 10L, token).contains("720/index.m3u8?playbackToken=" + token));
        assertTrue(streams.reviewPlaylist(1L, 720, 10L, token).contains("https://storage.example/"));
        verify(storage).presignReadUrl(mediaKey, 1200, SdkHttpMethod.GET);

        assertThrows(BizException.class, () -> streams.playback(1L, 10L));
        String view = tokens.issue(10, 1, 12000, PlaybackTokens.Scope.VIEW).token();
        assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BizException.class,
                () -> streams.reviewPlaylist(1L, 720, 10L, view)).getErrorCode());
        assertThrows(BizException.class, () -> streams.reviewMasterPlaylist(1L, 9L, token));
        content.setIsDelete(1);
        assertThrows(BizException.class, () -> streams.reviewPlaylist(1L, 720, 10L, token));
    }

    @Test
    void reviewReportsNotReadyInsteadOfFallingBackToTheUploadedFile() {
        content.setStatus(ContentStatusEnum.REVIEW);
        video.setPlaybackJson(null);
        assertEquals(ErrorCode.STATE_CONFLICT, assertThrows(BizException.class,
                () -> streams.reviewPlayback(1L, 9L)).getErrorCode());
        verifyNoInteractions(storage);
    }

    @Test
    void sourcesExposeOnlyRegisteredHlsIncludingSourceResolution() {
        VideoPlaybackVO response = streams.playback(1L, 10L);
        assertEquals(1920, response.width());
        assertEquals(1080, response.height());
        assertTrue(response.adaptivePlaylistUrl().startsWith("/contents/1/streams/master.m3u8?playbackToken="));
        String token = response.adaptivePlaylistUrl().split("playbackToken=")[1];
        PlaybackTokens.Grant grant = tokens.verify(token);
        assertNotNull(grant);
        assertEquals(10L, grant.userId());
        assertEquals(1L, grant.contentId());
        assertEquals(response.expiresAt(), grant.expiresAt());
        assertEquals(List.of(new VideoPlaybackVO.Rendition(720, 1280, 720, "/contents/1/streams/720/index.m3u8?playbackToken=" + token),
                new VideoPlaybackVO.Rendition(0, 1920, 1080, "/contents/1/streams/0/index.m3u8?playbackToken=" + token)), response.renditions());
        verifyNoInteractions(storage);
    }

    @Test
    void playbackVersionIsStableUntilPreparedResourcesChange() {
        String version = streams.playback(1L, 10L).playbackVersion();
        assertTrue(version.matches("[0-9a-f]{32}"));
        video.setDurationMs(13000L);
        assertEquals(version, streams.playback(1L, 10L).playbackVersion());
        video.setPlaybackJson(video.getPlaybackJson().replace("/101/", "/102/"));
        assertNotEquals(version, streams.playback(1L, 10L).playbackVersion());
        verifyNoInteractions(storage);
    }

    @Test
    void smallVideoStillProvidesSourceHlsAndMaster() {
        video.setPlaybackJson(JsonUtil.toJson(new VideoPlayback(320, 180, List.of(
                new VideoPlayback.Rendition(0, sourceMediaKey, sourcePlaylistKey, 320, 180, 1000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 1000000, 25))))));
        assertEquals(1, streams.playback(1L, 10L).renditions().size());
        assertEquals(0, streams.playback(1L, 10L).renditions().get(0).index());
        assertTrue(streams.playback(1L, 10L).adaptivePlaylistUrl().startsWith("/contents/1/streams/master.m3u8?playbackToken="));
        assertTrue(streams.masterPlaylist(1L, 10L, TOKEN).contains("RESOLUTION=320x180\n0/index.m3u8"));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, () -> streams.playlist(1L, 720, 9L, TOKEN)).getErrorCode());
        verify(storage, never()).readObject(anyString(), any(Duration.class));
    }

    @Test
    void playlistSignsOnceForInitAndAllMediaRanges() {
        String response = streams.playlist(1L, 720, 9L, TOKEN);
        String media = "https://storage.example/" + mediaKey + "?signature=test";
        assertTrue(response.contains("#EXT-X-MAP:URI=\"" + media + "\",BYTERANGE=\"800@0\""));
        assertTrue(response.contains("#EXT-X-BYTERANGE:2000@800\n" + media + "\n"));
        assertTrue(response.endsWith("#EXT-X-ENDLIST\n"));
        assertFalse(response.contains("playbackToken="));
        verify(storage).readObject(eq(playlistKey), any(Duration.class));
        verify(storage).presignReadUrl(mediaKey, 1200, SdkHttpMethod.GET);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void removedMediaRedirectCannotBeCalled() throws Exception {
        mvc.perform(get("/contents/1/streams/720/media.mp4")).andExpect(status().isNotFound());
        mvc.perform(head("/contents/1/streams/720/media.mp4")).andExpect(status().isNotFound());
        verifyNoInteractions(contents, videos, storage);
    }

    @Test
    void eachPlaylistRefreshRechecksVisibilityBeforeSigning() throws Exception {
        UserContext.setUser("10");
        String token = tokens.issue(10, 1, 12000, PlaybackTokens.Scope.VIEW).token();
        mvc.perform(get("/contents/1/streams/720/index.m3u8").header(PlaybackTokens.INTERNAL_HEADER, token))
                .andExpect(status().isOk());
        content.setVisibility("private");
        mvc.perform(get("/contents/1/streams/720/index.m3u8").header(PlaybackTokens.INTERNAL_HEADER, token))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        verify(storage).presignReadUrl(mediaKey, 1200, SdkHttpMethod.GET);
    }

    @Test
    void sourcePlaylistOnlySignsRegisteredObject() throws Exception {
        mvc.perform(get("/contents/1/streams/0/index.m3u8").param("objectKey", "other-video"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://storage.example/" + sourceMediaKey)));
        verify(storage).presignReadUrl(sourceMediaKey, 1200, SdkHttpMethod.GET);
    }

    @Test
    void signatureLifetimeCannotExceedThePlaybackGrant() {
        PlaybackTokens signer = mock(PlaybackTokens.class);
        when(signer.verify("short-ticket")).thenReturn(new PlaybackTokens.Grant(9, 1,
                System.currentTimeMillis() - 1000, System.currentTimeMillis() + 60000,
                PlaybackTokens.Scope.VIEW, "short-ticket"));
        ContentStreamService service = new ContentStreamService(ContentResourceTestSupport.uncachedAccess(contents, audio), resources, storage, signer);
        service.playlist(1L, 720, 9L, "short-ticket");
        verify(storage).presignReadUrl(eq(mediaKey), longThat(seconds -> seconds > 0 && seconds <= 60), eq(SdkHttpMethod.GET));
    }

    @Test
    void invalidOrWrongViewerTicketsCannotSignAPlaylist() {
        for (String token : List.of("invalid", tokens.issue(10, 1, 12000, PlaybackTokens.Scope.VIEW).token(),
                tokens.issue(9, 2, 12000, PlaybackTokens.Scope.VIEW).token(),
                tokens.issue(9, 1, 12000, PlaybackTokens.Scope.REVIEW).token())) {
            assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(BizException.class,
                    () -> streams.playlist(1L, 720, 9L, token)).getErrorCode());
        }
        verifyNoInteractions(contents, videos, storage);
    }

    @Test
    void signatureFailureDoesNotReturnAnUnsignedPlaylist() throws Exception {
        when(storage.presignReadUrl(mediaKey, 1200, SdkHttpMethod.GET))
                .thenThrow(new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "生成媒体访问签名失败", null));
        mvc.perform(get("/contents/1/streams/720/index.m3u8")).andExpect(status().isBadGateway())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void privateVideoRejectsOtherViewersBeforeReadingOrSigningResources() {
        content.setVisibility("private");
        assertThrows(BizException.class, () -> streams.playback(1L, 10L));
        assertThrows(BizException.class, () -> streams.playlist(1L, 720, 10L, tokens.issue(10, 1, 12000, PlaybackTokens.Scope.VIEW).token()));
        verifyNoInteractions(videos, storage);
        assertEquals(2, streams.playback(1L, 9L).renditions().size());
    }

    @Test
    void deletedUnpublishedAndBannedAudioVideosDenyBothEndpoints() {
        content.setIsDelete(1);
        assertDenied();
        content.setIsDelete(0).setStatus(ContentStatusEnum.REVIEW);
        assertDenied();
        content.setStatus(ContentStatusEnum.PUBLISHED);
        when(audio.isReferenceBlocked(content)).thenReturn(true);
        assertDenied();
        verifyNoInteractions(videos, storage);
    }

    @Test
    void dynamicAndMissingVideosDoNotReadMediaFiles() {
        content.setType(ContentTypeEnum.DYNAMIC);
        assertDenied();
        verifyNoInteractions(videos, storage);
        content.setType(ContentTypeEnum.VIDEO);
        when(videos.selectById(1L)).thenReturn(null);
        assertDenied();
        verifyNoInteractions(storage);
    }

    @Test
    void unknownRenditionDoesNotReadOrSignArbitraryStorageObjects() {
        for (int index : List.of(-1, 1, 480, 1080)) {
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class, () -> streams.playlist(1L, index, 9L, TOKEN)).getErrorCode());
        }
        verifyNoInteractions(storage);
    }

    @Test
    void masterListsAllHlsQualitiesIncludingSourceAndExcludesUploadedFile() {
        video.setPlaybackJson(JsonUtil.toJson(new VideoPlayback(1920, 1080, List.of(
                new VideoPlayback.Rendition(480, "480.mp4", "480.m3u8", 854, 480, 1000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 1000000, 25)),
                new VideoPlayback.Rendition(720, mediaKey, playlistKey, 1280, 720, 3000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 3000000, 25)),
                new VideoPlayback.Rendition(0, sourceMediaKey, sourcePlaylistKey, 1920, 1080, 5000000, new PlaybackMetadata("avc1.64001f,mp4a.40.2", 5000000, 25))))));
        String master = streams.masterPlaylist(1L, 10L, TOKEN);
        assertTrue(master.startsWith("#EXTM3U\n"));
        assertTrue(master.contains("#EXT-X-STREAM-INF:BANDWIDTH=1000000,AVERAGE-BANDWIDTH=1000000,CODECS=\"avc1.64001f,mp4a.40.2\",FRAME-RATE=25.000,RESOLUTION=854x480\n480/index.m3u8"));
        assertTrue(master.contains("#EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=3000000,CODECS=\"avc1.64001f,mp4a.40.2\",FRAME-RATE=25.000,RESOLUTION=1280x720\n720/index.m3u8"));
        assertTrue(master.contains("#EXT-X-STREAM-INF:BANDWIDTH=5000000,AVERAGE-BANDWIDTH=5000000,CODECS=\"avc1.64001f,mp4a.40.2\",FRAME-RATE=25.000,RESOLUTION=1920x1080\n0/index.m3u8"));
        assertFalse(master.contains(originalKey));
        verifyNoInteractions(storage);
    }

    @Test
    void masterUsesSilentOutputMetadataAndLocaleIndependentFrameRate() {
        PlaybackMetadata metadata = new PlaybackMetadata("avc1.64001f", 800000, 30000.0 / 1001);
        video.setPlaybackJson(JsonUtil.toJson(new VideoPlayback(320, 180, List.of(
                new VideoPlayback.Rendition(0, sourceMediaKey, sourcePlaylistKey, 320, 180, 1000000, metadata)))));
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE);
            String master = streams.masterPlaylist(1L, 10L, TOKEN);
            assertTrue(master.contains("BANDWIDTH=1000000,AVERAGE-BANDWIDTH=800000"));
            assertTrue(master.contains("CODECS=\"avc1.64001f\",FRAME-RATE=29.970"));
            assertFalse(master.contains("mp4a"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void httpContractsReturnUncachedJsonAndRawHls() throws Exception {
        mvc.perform(get("/contents/1/sources")).andExpect(status().isNotFound());
        mvc.perform(get("/contents/1/streams/720/index.m3u8")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/vnd.apple.mpegurl"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("#EXTM3U\n")));
        mvc.perform(get("/contents/1/streams/master.m3u8")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/vnd.apple.mpegurl"));
        mvc.perform(get("/contents/1/streams/0/index.m3u8")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://storage.example/" + sourceMediaKey)));
    }

    @Test
    void httpEndpointsRequireAuthenticationAndRecheckVisibility() throws Exception {
        UserContext.removeUser();
        mvc.perform(get("/contents/1/streams/720/index.m3u8")).andExpect(status().isUnauthorized());
        mvc.perform(get("/contents/1/streams/master.m3u8")).andExpect(status().isUnauthorized());
        mvc.perform(get("/contents/1/streams/0/index.m3u8")).andExpect(status().isUnauthorized());
        verifyNoInteractions(contents, videos, storage);
        UserContext.setUser("10");
        content.setVisibility("private");
        mvc.perform(get("/contents/1/streams/720/index.m3u8").header(PlaybackTokens.INTERNAL_HEADER, tokens.issue(10, 1, 12000, PlaybackTokens.Scope.VIEW).token())).andExpect(status().isNotFound());
        mvc.perform(get("/contents/1/streams/master.m3u8")).andExpect(status().isNotFound());
        mvc.perform(get("/contents/1/streams/0/index.m3u8").header(PlaybackTokens.INTERNAL_HEADER, tokens.issue(10, 1, 12000, PlaybackTokens.Scope.VIEW).token())).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    private void assertDenied() {
        assertThrows(BizException.class, () -> streams.playback(1L, 10L));
        assertThrows(BizException.class, () -> streams.playlist(1L, 720, 9L, TOKEN));
        assertThrows(BizException.class, () -> streams.masterPlaylist(1L, 10L, TOKEN));
    }
}

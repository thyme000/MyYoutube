package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackMetadata;
import cn.diaojiahong.myyoutube.common.media.VideoMedia;
import cn.diaojiahong.myyoutube.common.media.VideoPlayback;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpMethod;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentStreamExpiryTest {
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final ContentMediaResources resources = mock(ContentMediaResources.class);
    private final S3StorageUtil storage = mock(S3StorageUtil.class);
    private final PlaybackTokens tokens = mock(PlaybackTokens.class);
    private final PlaybackTokens.Grant grant = mock(PlaybackTokens.Grant.class);
    private final ContentStreamService streams = new ContentStreamService(access, resources, storage, tokens);
    private final AtomicBoolean readCompleted = new AtomicBoolean();

    @BeforeEach
    void setup() {
        when(tokens.verify("ticket")).thenReturn(grant);
        when(grant.scope()).thenReturn(PlaybackTokens.Scope.VIEW);
        when(grant.contentId()).thenReturn(1L);
        when(grant.userId()).thenReturn(9L);
        Content content = new Content().setId(1L).setType(ContentTypeEnum.VIDEO);
        when(access.readable(1L, 9L)).thenReturn(content);
        VideoPlayback playback = new VideoPlayback(1280, 720, List.of(new VideoPlayback.Rendition(
                720, "media.mp4", "index.m3u8", 1280, 720, 1000,
                new PlaybackMetadata("avc1.64001f", 1000, 25))));
        when(resources.media(content)).thenReturn(new ContentMediaResource(
                new ContentMedia("video", new VideoMedia("original.mp4", 12000), null), playback, "version"));
        when(resources.playlist("index.m3u8")).thenAnswer(call -> {
            readCompleted.set(true);
            return "#EXTM3U\nmedia.mp4\n";
        });
    }

    @Test
    void lessThanOneSecondRemainingRejectsBeforeAnyResourceRead() {
        when(grant.expiresAt()).thenAnswer(call -> System.currentTimeMillis() + 500);

        BizException failure = assertThrows(BizException.class, () -> streams.playlist(1L, 720, 9L, "ticket"));

        assertEquals(ErrorCode.UNAUTHORIZED, failure.getErrorCode());
        verifyNoInteractions(access, resources, storage);
    }

    @Test
    void grantExpiringDuringResourceReadCannotSignMedia() {
        when(grant.expiresAt()).thenAnswer(call ->
                System.currentTimeMillis() + (readCompleted.get() ? 500 : 60000));

        BizException failure = assertThrows(BizException.class, () -> streams.playlist(1L, 720, 9L, "ticket"));

        assertEquals(ErrorCode.UNAUTHORIZED, failure.getErrorCode());
        assertTrue(readCompleted.get());
        verifyNoInteractions(storage);
    }

    @Test
    void signingUsesTheLifetimeRemainingAfterTheResourceRead() {
        when(grant.expiresAt()).thenAnswer(call ->
                System.currentTimeMillis() + (readCompleted.get() ? 5500 : 60000));
        when(storage.presignReadUrl(anyString(), anyLong(), any(SdkHttpMethod.class))).thenReturn("signed-media");

        assertTrue(streams.playlist(1L, 720, 9L, "ticket").contains("signed-media"));

        verify(storage).presignReadUrl(eq("media.mp4"), longThat(seconds -> seconds > 0 && seconds <= 5), eq(SdkHttpMethod.GET));
    }
}

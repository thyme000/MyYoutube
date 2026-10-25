package cn.diaojiahong.myyoutube.other;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.other.service.impl.OtherService;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoUploadPresignTest {
    final S3StorageUtil storage = mock(S3StorageUtil.class);
    final MediaDraftPort drafts = mock(MediaDraftPort.class);
    final OtherService service = new OtherService(storage, drafts);

    @AfterEach void cleanup() { UserContext.removeUser(); }

    @Test void videoPutBindsDeclaredSizeAndTypeAndCreatesDraft() {
        UserContext.setUser("1");
        when(storage.buildUniqueObjectKey(1L, "video", "clip.mp4")).thenReturn("uploads/video/test.mp4");
        when(storage.presignPutUrl("uploads/video/test.mp4", 1800, 188688858, "video/mp4")).thenReturn("signed-put");
        when(drafts.createDraft(any())).thenReturn(new MediaDraftPort.DraftCreated(12L));
        var result = service.presignUpload("work", "clip.mp4", 188688858L, "video/mp4");
        assertEquals("put", result.getUploadType());
        assertEquals("signed-put", result.getUrl());
        assertEquals(12L, result.getDraftId());
        assertNull(result.getFormFields());
        verify(storage).presignPutUrl("uploads/video/test.mp4", 1800, 188688858, "video/mp4");
    }

    @Test void invalidMetadataCannotCreateDraftOrIssueSignature() {
        for (Long size : new Long[]{null, 0L, -1L, 500L * 1024 * 1024 + 1})
            assertThrows(BizException.class, () -> service.presignUpload("work", "clip.mp4", size, "video/mp4"));
        for (String type : new String[]{null, "", "text/plain", "video/mp4\r\nInjected: bad"})
            assertThrows(BizException.class, () -> service.presignUpload("work", "clip.mp4", 1024L, type));
        verifyNoInteractions(storage, drafts);
    }

    @Test void nonVideoUploadDoesNotRequireVideoMetadataOrCreateDraft() {
        UserContext.setUser("1");
        when(storage.buildUniqueObjectKey(1L, "picture", "image.jpg")).thenReturn("picture/test.jpg");
        when(storage.presignPutUrl("picture/test.jpg", 1800)).thenReturn("signed-picture");
        assertEquals("signed-picture", service.presignUpload("picture", "image.jpg", null, null).getUrl());
        verifyNoInteractions(drafts);
    }

    @Test void audioPutBindsSizeAndTypeWithoutCreatingVideoDraft() {
        UserContext.setUser("1");
        when(storage.buildUniqueObjectKey(1L, "audio", "music.mp3")).thenReturn("uploads/audio/1/day/music.mp3");
        when(storage.presignPutUrl("uploads/audio/1/day/music.mp3", 1800, 1024, "audio/mpeg")).thenReturn("signed-audio");
        assertEquals("signed-audio", service.presignUpload("audio", "music.mp3", 1024L, "audio/mpeg").getUrl());
        verify(storage).presignPutUrl("uploads/audio/1/day/music.mp3", 1800, 1024, "audio/mpeg");
        verifyNoInteractions(drafts);
    }

    @Test void audioCannotIssueUnboundedUploadSignatures() {
        for (Long size : new Long[]{null, 0L, -1L, 128L * 1024 * 1024 + 1})
            assertThrows(BizException.class, () -> service.presignUpload("audio", "music.mp3", size, "audio/mpeg"));
        for (String type : new String[]{null, "", "video/mp4", "audio/mpeg\r\nInjected: bad"})
            assertThrows(BizException.class, () -> service.presignUpload("audio", "music.mp3", 1024L, type));
        verifyNoInteractions(storage, drafts);
    }
}

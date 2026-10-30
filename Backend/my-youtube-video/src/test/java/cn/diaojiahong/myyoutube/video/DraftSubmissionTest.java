package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.support.ContentSubmissionRules;
import cn.diaojiahong.myyoutube.content.service.ContentSubmissionService;
import cn.diaojiahong.myyoutube.content.strategy.*;
import cn.diaojiahong.myyoutube.video.api.enums.*;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.ContentUnderstandingService;
import cn.diaojiahong.myyoutube.video.service.impl.MediaDraftService;
import java.time.LocalDateTime;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DraftSubmissionTest {
    final MediaDraftService drafts = mock(MediaDraftService.class);

    final VideoMapper videos = mock(VideoMapper.class);

    final ContentMapper contents = mock(ContentMapper.class);

    final ContentUnderstandingService understanding = mock(ContentUnderstandingService.class);

    final TransactionTemplate tx = mock(TransactionTemplate.class);

    final ContentSubmissionService service = new ContentSubmissionService(drafts, contents,
            new ContentTypeStrategyRegistry(List.of(new VideoContentStrategy(videos))),
            understanding,
            tx,
            new ContentSubmissionRules("content-moderation/blocked.txt"));
    MediaDraft draft;

    @BeforeEach
    void setup() {
        draft = new MediaDraft().setId(1L).setUserId(9L).setType(ContentTypeEnum.VIDEO).setTitle("标题")
                .setSubmissionState(DraftSubmissionState.PENDING)
                .setSubmissionRevision(0)
                .setSubmissionAfter(java.time.LocalDateTime.now().plusSeconds(30))
                .setSubmissionDeadline(java.time.LocalDateTime.now().plusMinutes(30))
                .setExpiresAt(java.time.LocalDateTime.now().plusDays(7))
                .setRevision(0)
                .setObjectKey("original.mp4")
                .setStatus(MediaDraftStatusEnum.READY)
                .setVisibility("public")
                .setAllowComment(1);
        when(drafts.getById(1L)).thenAnswer(call -> draft);
        when(drafts.submit(any(), anyLong())).thenReturn(true);
        when(understanding.enroll(any(), any())).thenReturn(100L);
        when(tx.execute(any()))
                .thenAnswer(call -> ((org.springframework.transaction.support.TransactionCallback<?>)call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
    }

    void prepared() {
        draft.setPreparationId(101L).setMediaVersion(0L);
        PreparedDraftTestSupport.ready(draft);
    }

    @Test
    void missingOrUnacceptedClaimCannotBypassPublicationIntent() {
        LocalDateTime lease = draft.getSubmissionAfter();
        assertThrows(BizException.class, () -> service.submitRequested(1L, 9L, null, lease));
        assertThrows(BizException.class, () -> service.submitRequested(1L, 9L, 0, null));
        draft.setSubmissionState(DraftSubmissionState.NONE);
        assertThrows(BizException.class, () -> service.submitRequested(1L, 9L, 0, lease));
        verify(drafts, never()).submit(any(), anyLong());
        verifyNoInteractions(videos, contents, understanding);
    }

    @Test
    void missingPreparationFailsWithoutPublishingOriginalFile() {
        assertEquals(cn.diaojiahong.myyoutube.common.result.ErrorCode.DRAFT_PREPARATION_FAILED,
                assertThrows(BizException.class, () -> service.submitRequested(1L, 9L, 0, draft.getSubmissionAfter())).getErrorCode()); verifyNoInteractions(videos, contents, understanding);
    }

    @Test
    void frozenOriginalAndCommonMetadataHaveOneOwner() {
        prepared();
        draft.setDescription("简介").setVisibility("private").setAllowComment(0);
        assertEquals(100L, service.submitRequested(1L, 9L, 0, draft.getSubmissionAfter()).pipelineId());
        ArgumentCaptor<Video> media = org.mockito.ArgumentCaptor.forClass(Video.class);
        verify(videos).insert(media.capture());
        assertEquals("media-prepared/9/101/video", media.getValue().getObjectKey());
        assertEquals(1501L,media.getValue().getDurationMs());
        ArgumentCaptor<Content> common = org.mockito.ArgumentCaptor.forClass(cn.diaojiahong.myyoutube.content.domain.po.Content.class);
        verify(contents).insert(common.capture());
        assertEquals("简介",common.getValue().getDescription());
        assertEquals("public",common.getValue().getVisibility());
        assertEquals(ContentStatusEnum.REVIEW,common.getValue().getStatus());
        assertEquals(0,common.getValue().getAllowComment());
        assertNull(common.getValue().getCoverObjectKey());
    }

    @Test
    void repeatedSubmissionReturnsEnrollmentWithoutPreparingMedia() {
        draft.setStatus(MediaDraftStatusEnum.SUBMITTED);
        when(understanding.get(1L)).thenReturn(new ContentUnderstanding().setContentId(1L).setPipelineId(100L));
        assertEquals(
                new ContentSubmissionService.Receipt(1L,100L,"submitted"),
                service.submitRequested(1L, 9L, 0, draft.getSubmissionAfter()));
        verifyNoInteractions(videos,contents);
        verify(understanding,never()).enroll(any(),any());
    }

    @Test
    void ownershipProtectsSubmittedAndReadyDrafts() {
        assertThrows(BizException.class, () -> service.submitRequested(1L, 10L, 0, draft.getSubmissionAfter()));
        draft.setStatus(MediaDraftStatusEnum.SUBMITTED);
        assertThrows(BizException.class, () -> service.submitRequested(1L, 10L, 0, draft.getSubmissionAfter()));
        verifyNoInteractions(videos,contents,understanding);
    }

    @Test
    void invalidSavedMetadataFailsBeforeReadingMedia() {
        draft.setAllowComment(2);
        assertThrows(BizException.class, () -> service.submitRequested(1L, 9L, 0, draft.getSubmissionAfter()));
        draft.setAllowComment(1).setTitle(" ");
        assertThrows(BizException.class, () -> service.submitRequested(1L, 9L, 0, draft.getSubmissionAfter()));
        verifyNoInteractions(videos,contents,understanding);
    }
}

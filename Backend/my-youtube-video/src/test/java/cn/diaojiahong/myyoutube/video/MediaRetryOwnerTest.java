package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.video.api.MediaRetryOwnerPort.*;
import cn.diaojiahong.myyoutube.video.controller.InternalMediaRetryOwnerController;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.video.domain.po.*;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentReviewState;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.config.ContentPipelineProperties;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaRetryOwnerTest {
    final MediaDraftMapper drafts=mock(MediaDraftMapper.class);
    final ContentUnderstandingMapper understanding=mock(ContentUnderstandingMapper.class);
    final IContentService contents=mock(IContentService.class);
    final ContentPipelineProperties policy=new ContentPipelineProperties();
    final InternalMediaRetryOwnerController controller=new InternalMediaRetryOwnerController(drafts,understanding,contents,policy);
    @Test void draftVersionExpiryAndSubmissionAreFenced() {
        var owner=new Owner("DRAFT",1L,null,2L,3L);
        assertEquals("OWNER_DELETED",controller.check(owner).reason());
        var draft=new MediaDraft().setId(1L).setPreparationId(2L).setMediaVersion(3L)
                .setStatus(MediaDraftStatusEnum.FAILED).setExpiresAt(LocalDateTime.now().plusDays(1));
        when(drafts.selectById(1L)).thenReturn(draft);
        assertTrue(controller.check(owner).allowed());
        draft.setMediaVersion(4L);
        assertEquals("OWNER_VERSION_CHANGED",controller.check(owner).reason());
        draft.setMediaVersion(3L).setStatus(MediaDraftStatusEnum.SUBMITTED);
        assertEquals("DRAFT_STATE_CHANGED",controller.check(owner).reason());
        draft.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertEquals("OWNER_EXPIRED",controller.check(owner).reason());
    }
    @Test void manualAndFinalReviewStatesCannotBeReopened() {
        var owner=new Owner("REVIEW",1L,100L,null,null);
        when(contents.getById(1L)).thenReturn(new Content().setId(1L).setIsDelete(0));
        var row=new ContentUnderstanding().setContentId(1L).setPipelineId(100L).setReceived(true);
        when(understanding.selectById(1L)).thenReturn(row);
        policy.setAutoReview(true);
        for (var state:new ContentReviewState[]{ContentReviewState.MANUAL_PENDING,ContentReviewState.APPROVED,ContentReviewState.REJECTED,ContentReviewState.CANCELLED}) {
            row.setState(state);
            assertEquals("MANUAL_REVIEW_LOCKED",controller.check(owner).reason());
        }
        row.setState(ContentReviewState.AUTO_PENDING);
        assertTrue(controller.check(owner).allowed());
        policy.setAutoReview(false);
        assertFalse(controller.check(owner).allowed());
    }
    @Test void contentDeletionAndPipelineReplacementPreventRetry() {
        var owner=new Owner("CONTENT",1L,100L,null,null);
        when(contents.getById(1L)).thenReturn(new Content().setId(1L).setIsDelete(1));
        assertEquals("OWNER_DELETED",controller.check(owner).reason());
        when(contents.getById(1L)).thenReturn(new Content().setId(1L).setIsDelete(0));
        when(understanding.selectById(1L)).thenReturn(new ContentUnderstanding().setPipelineId(200L));
        assertEquals("OWNER_VERSION_CHANGED",controller.check(owner).reason());
    }
}

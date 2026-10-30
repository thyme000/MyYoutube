package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentDetailVO;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.*;
import cn.diaojiahong.myyoutube.content.service.impl.ContentFeedServiceImpl;
import cn.diaojiahong.myyoutube.content.strategy.*;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.service.ContentUnderstandingService;
import cn.diaojiahong.myyoutube.video.mapper.ContentReviewRecordMapper;
import cn.diaojiahong.myyoutube.transcode.api.TranscodePort;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import cn.diaojiahong.myyoutube.transcode.api.enums.TranscodeTaskStatus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentReadContractTest {
    final ContentMapper contents=mock(ContentMapper.class);

    final S3StorageUtil storage=mock(S3StorageUtil.class);

    final ContentUnderstandingService understanding=mock(ContentUnderstandingService.class);

    final ContentTypeStrategy strategy=mock(ContentTypeStrategy.class);

    final ContentTypeStrategyRegistry registry=new ContentTypeStrategyRegistry(List.of());

    @Test
    void bannedAudioBlocksDetailsSubtitlesAndBothNewAndPreviouslyCompletedDownloads() {
        Content row = new Content().setId(1L).setType(ContentTypeEnum.DYNAMIC).setAuthorId(9L)
                .setVisibility("public").setStatus(ContentStatusEnum.PUBLISHED).setIsDelete(0);
        when(contents.selectById(1L)).thenReturn(row);
        AudioContentAccess audios = mock(AudioContentAccess.class);
        when(audios.blocked(anyList())).thenReturn(Set.of(1L));
        var access = new ContentAccessService(contents, audios);
        var reads = new ContentReadService(
                access,
                registry,
                mock(ContentFeedServiceImpl.class),
                understanding,
                storage);
        TranscodePort transcode = mock(TranscodePort.class);
        var task = new TranscodeTaskVO();
        task.setTaskId(5L);
        task.setContentId(1L);
        task.setUserId(9L);
        task.setStatus(TranscodeTaskStatus.DONE);
        task.setResultObjectKey("download/already-done.mp4");
        when(transcode.getTask(5L)).thenReturn(task);
        var downloads = new ContentDownloadService(access, registry, transcode, storage);
        assertThrows(BizException.class, () -> reads.detail(1L, 9L));
        assertThrows(BizException.class, () -> reads.subtitles(1L, 9L));
        assertThrows(BizException.class, () -> downloads.create(1L, 9L));
        assertThrows(BizException.class, () -> downloads.status(5L, 9L));
        verifyNoInteractions(storage, strategy, understanding);
        verify(transcode).getTask(5L);
        verifyNoMoreInteractions(transcode);
    }

    @Test
    void nativeDetailAndCardReadSameMetadataAndSignTypedMedia() {
        when(strategy.type()).thenReturn(ContentTypeEnum.DYNAMIC);
        var registry=new ContentTypeStrategyRegistry(List.of(strategy));
        Content content=new Content().setId(1L).setType(ContentTypeEnum.DYNAMIC).setAuthorId(7L).setTitle("标题")
                .setDescription("动态图集简介")
                .setCoverObjectKey("second")
                .setAllowComment(0)
                .setVisibility("public")
                .setStatus(ContentStatusEnum.PUBLISHED)
                .setIsDelete(0);
        when(contents.selectById(1L)).thenReturn(content);
        var dynamic=new DynamicMedia("vertical",1080,1920,"cut",0,List.of(
                new DynamicMedia.Image("first",0,3000,0.5,0.5), new DynamicMedia.Image("second",3000,3000,0.5,0.5)),
                new DynamicMedia.Audio(20L,"music","原配乐",120000),6000);
        when(strategy.loadMedia(List.of(1L))).thenReturn(Map.of(1L,new ContentMedia("dynamic",null,dynamic)));
        when(storage.signGetUrl(anyString(),anyLong())).thenAnswer(call -> "signed/"+call.getArgument(0));
        AudioContentAccess audios = mock(AudioContentAccess.class);
        var cards=new ContentFeedServiceImpl(mock(IContentService.class),mock(ContentLikeMapper.class),
                mock(IUserFollowService.class),
                mock(UserPort.class),
                storage,
                mock(RecommendPort.class),
                registry,
                audios,
                mock(ContentReviewRecordMapper.class),
                mock(AuthorContentPageService.class));
        var service=new ContentReadService(
                new ContentAccessService(contents,audios),
                registry,
                cards,
                understanding,
                storage);
        ContentDetailVO detail=service.detail(1L,9L);
        assertEquals("1",detail.getId());
        assertEquals("dynamic",detail.getType());
        assertEquals("动态图集简介",detail.getDescription());
        assertEquals(0,detail.getAllowComment());
        assertEquals("signed/second",detail.getCoverUrl());
        assertEquals(6000L,detail.getMedia().dynamic().exportDurationMs());
        assertNull(detail.getMedia().video());
        assertEquals("dynamic",detail.getMedia().type());
        assertEquals(
                List.of("signed/first","signed/second"),
                detail.getMedia().dynamic().items().stream().map(i -> i.url()).toList());
        assertEquals(120000L,detail.getMedia().dynamic().audio().durationMs());
        assertEquals("signed/music",detail.getMedia().dynamic().audio().url());
        verify(audios, times(1)).blocked(List.of(content));
    }

    @Test
    void reviewMediaIncludesNativeImagesBeforePublicationButRejectsDeletedContent() {
        when(strategy.type()).thenReturn(ContentTypeEnum.DYNAMIC);
        var registry = new ContentTypeStrategyRegistry(List.of(strategy));
        Content content = new Content()
                .setId(1L)
                .setType(ContentTypeEnum.DYNAMIC)
                .setStatus(ContentStatusEnum.REVIEW)
                .setIsDelete(0);
        when(contents.selectById(1L)).thenReturn(content);
        var media = new DynamicMedia(
                "vertical",
                1080,
                1920,
                "cut",
                0,
                List.of(new DynamicMedia.Image("image",0,3000,.5,.5)),
                null,
                3000);
        when(strategy.loadMedia(List.of(1L))).thenReturn(Map.of(1L,new ContentMedia("dynamic",null,media)));
        when(storage.signGetUrl("image",1200)).thenReturn("signed/image");
        var service = new ContentReadService(
                new ContentAccessService(contents,mock(AudioContentAccess.class)),
                registry,
                mock(ContentFeedServiceImpl.class),
                understanding,
                storage);
        assertEquals("signed/image", service.adminMedia(1L).dynamic().items().get(0).url());
        content.setIsDelete(1);
        assertThrows(BizException.class, () -> service.adminMedia(1L));
    }

    @Test
    void privateDetailAndSubtitleRefuseBeforeLoadingArtifactsOrSigningUrls() {
        when(contents.selectById(1L))
                .thenReturn(new Content().setId(1L).setAuthorId(7L).setVisibility("private").setStatus(ContentStatusEnum.PUBLISHED).setIsDelete(0));
        var service=new ContentReadService(
                new ContentAccessService(contents,mock(AudioContentAccess.class)),
                registry,
                mock(ContentFeedServiceImpl.class),
                understanding,
                storage);
        assertThrows(BizException.class, () -> service.detail(1L,9L));
        assertThrows(BizException.class, () -> service.subtitles(1L,9L));
        verifyNoInteractions(understanding,storage,strategy);
    }
}

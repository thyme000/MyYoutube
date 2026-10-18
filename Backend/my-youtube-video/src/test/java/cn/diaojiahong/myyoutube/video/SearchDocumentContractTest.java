package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.notification.service.NotificationPublisher;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.controller.InternalVideoController;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.mapper.ContentUnderstandingMapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchDocumentContractTest {
    @ParameterizedTest
    @EnumSource(ContentTypeEnum.class)
    void searchDocumentIncludesStoredSummaryForBothMediaTypes(ContentTypeEnum type) {
        var contents = mock(IContentService.class);
        var tags = mock(IContentTagService.class);
        LambdaQueryChainWrapper<ContentTag> tagQuery = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(tags.lambdaQuery()).thenReturn(tagQuery);
        doReturn(tagQuery).when(tagQuery).eq(any(), any());
        when(tagQuery.list()).thenReturn(List.of());
        var understandings = mock(ContentUnderstandingMapper.class);
        var content = new Content().setId(1L).setType(type).setTitle("标题").setDescription("简介")
                .setStatus("published").setVisibility("public").setIsDelete(0);
        when(contents.getById(1L)).thenReturn(content);
        when(understandings.selectById(1L)).thenReturn(new ContentUnderstanding().setSummary("语音与画面生成的摘要"));
        var controller = new InternalVideoController(tags, contents, understandings, mock(ContentLikeMapper.class),
                mock(ContentAccessService.class), mock(CounterBuffer.class), mock(IUserFollowService.class),
                mock(NotificationPublisher.class), mock(IUserTagPreferenceService.class));

        var document = controller.searchDocument(1L);
        assertEquals(type.getCode(), document.getType());
        assertEquals("标题", document.getTitle());
        assertEquals("简介", document.getDescription());
        assertEquals("语音与画面生成的摘要", document.getSummary());

        when(understandings.selectById(1L)).thenReturn(new ContentUnderstanding().setSummary(""));
        assertEquals("", controller.searchDocument(1L).getSummary());
        content.setIsDelete(1);
        assertNull(controller.searchDocument(1L));
        verify(understandings, times(2)).selectById(1L);
    }
}

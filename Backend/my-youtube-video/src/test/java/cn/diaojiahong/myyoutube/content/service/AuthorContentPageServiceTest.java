package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.domain.dto.AuthorContentCursor;
import cn.diaojiahong.myyoutube.content.domain.dto.AuthorContentCursorQuery;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentStatisticsMapper;
import cn.diaojiahong.myyoutube.video.api.enums.ContentVisibilityEnum;
import cn.diaojiahong.myyoutube.video.config.AuthorContentPageProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorContentPageServiceTest {
    private final IContentService contents = mock(IContentService.class);

    private final AudioContentAccess audioAccess = mock(AudioContentAccess.class);

    private final AuthorContentPageProperties properties = new AuthorContentPageProperties();

    private final ContentStatisticsMapper statistics = mock(ContentStatisticsMapper.class);

    private final AtomicLong clock = new AtomicLong();

    private final AuthorContentPageService service = new AuthorContentPageService(
            contents,
            audioAccess,
            statistics,
            properties,
            clock::get);

    private final AuthorContentCursorQuery query = new AuthorContentCursorQuery();

    AuthorContentPageServiceTest() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "author-page-test"),
                Content.class);
        query.setPageSize(2);
        when(statistics.displayCount(anyLong(), any(), any())).thenReturn(10L);
        when(audioAccess.blocked(anyList())).thenReturn(Set.of());
    }

    private Content row(long id) {
        return new Content().setId(id).setCreateTime(LocalDateTime.of(2026, 10, 1, 0, 0));
    }

    @Test
    void firstPageReadsTheSummaryWithoutScanningForCount() {
        when(contents.list(any(Wrapper.class))).thenReturn(List.of(row(10), row(9), row(8)));
        AuthorContentPageService.AuthorContentCandidates result = service.list(9L, 42L, query);
        assertEquals(10L, result.displayCount());
        assertEquals(9, AuthorContentCursor.decode(result.page().nextCursor(), 42L, 9L, null).contentId());
        verify(statistics).displayCount(42L, null, ContentVisibilityEnum.PUBLIC);
        verify(contents, times(1)).list(any(Wrapper.class));
        verify(contents, never()).count(any(Wrapper.class));
    }

    @Test
    void continuationReadsTheCurrentTotalWithoutScanningRemainingWorks() {
        query.setCursor(new AuthorContentCursor(42L, 9L, null, row(10).getCreateTime(), 10L).encode());
        when(contents.list(any(Wrapper.class))).thenReturn(List.of(row(9)));
        assertEquals(10L, service.list(9L, 42L, query).displayCount());
        verify(statistics).displayCount(42L, null, ContentVisibilityEnum.PUBLIC);
        verify(contents, never()).count(any(Wrapper.class));
    }

    @Test
    void summaryFailurePropagatesInsteadOfReturningAFabricatedCount() {
        when(statistics.displayCount(anyLong(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("summary unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.list(9L, 42L, query));
        verifyNoInteractions(contents);
    }

    @Test
    void initialLookupFailureDoesNotBecomeAnEmptyPage() {
        when(contents.list(any(Wrapper.class))).thenThrow(new DataAccessResourceFailureException("scan unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.list(9L, 42L, query));
    }

    @Test
    void failedRefillKeepsTheLastCheckedPositionAndAllowsRetry() {
        when(contents.list(any(Wrapper.class))).thenReturn(List.of(row(10), row(9), row(8)))
                .thenThrow(new DataAccessResourceFailureException("refill unavailable"));
        when(audioAccess.blocked(anyList())).thenReturn(Set.of(10L));
        AuthorContentPageService.AuthorContentCandidates result = service.list(9L, 42L, query);
        assertEquals(List.of(9L), result.page().items().stream().map(Content::getId).toList());
        assertEquals(9, AuthorContentCursor.decode(result.page().nextCursor(), 42L, 9L, null).contentId());
    }

    @Test
    void elapsedBudgetAllowsAShortEmptyPageWithAContinuation() {
        properties.setScanLimit(2);
        when(contents.list(any(Wrapper.class))).thenAnswer(call -> {
            clock.set(300_000_000L);
            return List.of(row(10), row(9), row(8));
        });
        when(audioAccess.blocked(anyList())).thenReturn(Set.of(10L, 9L));
        AuthorContentPageService.AuthorContentCandidates result = service.list(9L, 42L, query);
        assertTrue(result.page().items().isEmpty());
        assertEquals(10L, result.displayCount());
        assertNotNull(result.page().nextCursor());
        verify(contents, times(1)).list(any(Wrapper.class));
    }
}

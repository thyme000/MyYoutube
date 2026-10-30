package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import co.elastic.clients.elasticsearch._types.FieldValue;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagSearchFlowTest {
    private final KeywordRecallStrategy keyword = mock(KeywordRecallStrategy.class);

    private final SearchContentSummaryClient summaries = mock(SearchContentSummaryClient.class);

    private final RecallProperties properties = new RecallProperties();

    private final ContentSearchPage search = new ContentSearchPage(mock(HybridRecallService.class),
            new KeywordSearchPage(keyword, summaries, properties), summaries, properties);

    private CursorPage<ContentSummary, String> searchPage(SearchContext context, String cursor, int pageSize) {
        TagSearchCursorQuery query = new TagSearchCursorQuery();
        query.setTagName(context.getKeyword());
        query.setCursor(cursor);
        query.setPageSize(pageSize);
        return search.search(SearchContext.forTag(query, context.getUserId(), context.getFollowIds()));
    }

    private SearchContext context(String tag) {
        TagSearchCursorQuery query = new TagSearchCursorQuery();
        query.setTagName(tag);
        return SearchContext.forTag(query, 7L, List.of());
    }

    private List<FieldValue> position(long id) {
        return List.of(FieldValue.of(1.5), FieldValue.of("2026-01-01 00:00:00"), FieldValue.of(id));
    }

    private ContentSummary summary(long id) {
        ContentSummary row = new ContentSummary();
        row.setId(id);
        return row;
    }

    @Test
    void filtersAndRefillsWithoutSkippingTheReservedCandidate() throws Exception {
        when(keyword.load(any(), eq(List.of()), eq(3), anySet()))
                .thenReturn(new RecallBatch(List.of("1", "2", "3"), position(3)));
        when(keyword.load(
                any(),
                argThat(p -> p != null && p.size() == 3 && p.get(2).longValue() == 3),
                eq(1),
                anySet()))
                .thenReturn(new RecallBatch(List.of("4"), position(4)));
        when(keyword.load(
                any(),
                argThat(p -> p != null && p.size() == 3 && p.get(2).longValue() == 4),
                eq(3),
                anySet()))
                .thenReturn(RecallBatch.exhausted(List.of("5")));
        when(summaries.listByIdsOrdered(any())).thenAnswer(call -> {
            ContentSummaryPort.SummaryQuery query = call.getArgument(0);
            return query.ids().stream().filter(id -> !id.equals("2")).map(id -> summary(Long.parseLong(id))).toList();
        });
        CursorPage<ContentSummary, String> first = searchPage(context("camera"), null, 3);
        assertEquals(List.of(1L, 3L, 4L), first.items().stream().map(ContentSummary::getId).toList());
        assertNotNull(first.nextCursor());
        CursorPage<ContentSummary, String> second = searchPage(context("camera"), first.nextCursor(), 3);
        assertEquals(List.of(5L), second.items().stream().map(ContentSummary::getId).toList());
        assertNull(second.nextCursor());
        assertThrows(BizException.class, () -> searchPage(context("different"), first.nextCursor(), 3));
    }

    @Test
    void refillFailurePreservesTheLastCheckedPosition() throws Exception {
        when(keyword.load(any(), eq(List.of()), eq(3), anySet()))
                .thenReturn(new RecallBatch(List.of("1", "2", "3"), position(3)));
        when(keyword.load(
                any(),
                argThat(p -> p != null && p.size() == 3 && p.get(2).longValue() == 3),
                eq(2),
                anySet()))
                .thenThrow(new IllegalStateException("ES down"));
        when(summaries.listByIdsOrdered(any())).thenReturn(List.of(summary(1)));
        CursorPage<ContentSummary, String> page = searchPage(context("camera"), null, 3);
        assertEquals(List.of(1L), page.items().stream().map(ContentSummary::getId).toList());
        assertEquals(3, TagSearchCursor.decode(page.nextCursor(), context("camera")).get(2).longValue());
    }

    @Test
    void firstFailureIsNotReportedAsAnEmptyPage() throws Exception {
        when(keyword.load(any(), anyList(), anyInt(), anySet())).thenThrow(new IllegalStateException("ES down"));
        assertThrows(IllegalStateException.class, () -> searchPage(context("camera"), null, 3));
    }

    @Test
    void checkedFailureUsesTheTagSearchLabel() throws Exception {
        when(keyword.load(any(), anyList(), anyInt(), anySet())).thenThrow(new IOException("ES down"));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> searchPage(context("camera"), null, 3));
        assertEquals("标签搜索失败", failure.getMessage());
    }

    @Test
    void rejectsInvalidDateBeforeCallingEs() {
        String cursor = TagSearchCursor.encode(context("camera"),
                List.of(FieldValue.of(1.5), FieldValue.of("not-a-date"), FieldValue.of(2L)));
        assertThrows(BizException.class, () -> searchPage(context("camera"), cursor, 3));
        verifyNoInteractions(keyword, summaries);
    }

    @Test
    void cursorPreservesLargeIdsAndRejectsAnotherUser() {
        long id = 9007199254740993L;
        String cursor = TagSearchCursor.encode(context("camera"), position(id));
        assertEquals(id, TagSearchCursor.decode(cursor, context("camera")).get(2).longValue());
        TagSearchCursorQuery query = new TagSearchCursorQuery();
        query.setTagName("camera");
        assertThrows(
                BizException.class,
                () -> TagSearchCursor.decode(cursor, SearchContext.forTag(query, 8L, List.of())));
        assertThrows(BizException.class, () -> TagSearchCursor.decode("invalid", context("camera")));
    }

    @Test
    void traversesBeyondTheOldHundredCandidateLimit() throws Exception {
        when(keyword.load(any(), anyList(), anyInt(), anySet())).thenAnswer(call -> {
            List<FieldValue> cursor = call.getArgument(1);
            int size = call.getArgument(2);
            int start = cursor.isEmpty() ? 1 : (int) cursor.get(2).longValue() + 1;
            int end = Math.min(130, start + size - 1);
            List<String> ids = java.util.stream.IntStream.rangeClosed(start, end).mapToObj(Integer::toString).toList();
            return end < 130 ? new RecallBatch(ids, position(end)) : RecallBatch.exhausted(ids);
        });
        when(summaries.listByIdsOrdered(any())).thenAnswer(call -> {
            ContentSummaryPort.SummaryQuery query = call.getArgument(0);
            return query.ids().stream().map(id -> summary(Long.parseLong(id))).toList();
        });
        List<Long> ids = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<ContentSummary, String> page = searchPage(context("camera"), cursor, 10);
            ids.addAll(page.items().stream().map(ContentSummary::getId).toList());
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(java.util.stream.LongStream.rangeClosed(1, 130).boxed().toList(), ids);
    }
}

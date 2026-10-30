package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import co.elastic.clients.elasticsearch._types.FieldValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(5)
class ContentSearchPageTest {
    final KeywordRecallStrategy keyword = mock(KeywordRecallStrategy.class);

    final RecallStrategy vector = mock(RecallStrategy.class);

    final SearchContentSummaryClient summaries = mock(SearchContentSummaryClient.class);

    final RecallProperties properties = new RecallProperties();

    final Set<String> blocked = new HashSet<>();

    List<String> keywordIds = List.of("1", "2", "3", "4", "7", "8");

    final HybridRecallService hybrid = new HybridRecallService(
            List.of(keyword, vector),
            new RrfFuser(new VectorProperties()),
            properties, new SimpleMeterRegistry());

    final ContentSearchPage search = new ContentSearchPage(hybrid,
            new KeywordSearchPage(keyword, summaries, properties), summaries, properties);

    @BeforeEach
    void setup() throws Exception {
        properties.setFirstPageSize(2);
        when(keyword.shouldRun(any())).thenReturn(true);
        when(keyword.supportsPaging()).thenReturn(true);
        when(vector.shouldRun(any())).thenReturn(true);
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(
                new RecallBatch(List.of("1", "2", "3", "4"), position(false, "4"))));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of(
                "4",
                "6"))));
        when(summaries.listByIdsOrdered(any())).thenAnswer(call -> {
            ContentSummaryPort.SummaryQuery query = call.getArgument(0);
            return query.ids().stream().filter(id -> !blocked.contains(id)).map(this::summary).toList();
        });
        when(keyword.load(any(), anyList(), anyInt(), anySet())).thenAnswer(call -> {
            SearchContext context = call.getArgument(0);
            List<FieldValue> position = call.getArgument(1);
            int size = call.getArgument(2);
            Set<String> excluded = call.getArgument(3);
            List<String> candidates = keywordIds.stream().filter(id -> !excluded.contains(id)).toList();
            int start = position.isEmpty() ? 0 : candidates.indexOf(Long.toString(position.get(position.size() - 1).longValue())) + 1;
            List<String> ids = candidates.stream().skip(start).limit(size).toList();
            return start + ids.size() < candidates.size()
                    ? new RecallBatch(ids, position(context.isSortByLatest(), ids.get(ids.size() - 1))) : RecallBatch.exhausted(ids);
        });
    }

    ContentSummary summary(String id) {
        ContentSummary row = new ContentSummary();
        row.setId(Long.parseLong(id));
        return row;
    }

    List<FieldValue> position(boolean latest, String id) {
        return latest ? List.of(FieldValue.of(100L), FieldValue.of(Long.parseLong(id)))
                : List.of(FieldValue.of(1.5), FieldValue.of(100L), FieldValue.of(Long.parseLong(id)));
    }

    SearchContext context(String cursor, boolean latest, int size) {
        ContentSearchCursorQuery query = new ContentSearchCursorQuery();
        query.setKeyword("camera");
        query.setCursor(cursor);
        query.setSortByLatest(latest);
        query.setPageSize(size);
        return SearchContext.of(query, 7L, List.of());
    }

    List<Long> ids(CursorPage<ContentSummary, String> page) {
        return page.items().stream().map(ContentSummary::getId).toList();
    }

    @Test
    void mixedFirstPageThenKeywordPagesDoNotSkipOrRepeatTheOriginalCandidates() {
        CursorPage<ContentSummary, String> first = search.search(context(null, false, 2));
        assertEquals(List.of(4L, 1L), ids(first));
        CursorPage<ContentSummary, String> second = search.search(context(first.nextCursor(), false, 2));
        assertEquals(List.of(2L, 3L), ids(second));
        assertEquals(ids(second), ids(search.search(context(first.nextCursor(), false, 2))));
        CursorPage<ContentSummary, String> third = search.search(context(second.nextCursor(), false, 2));
        assertEquals(List.of(7L, 8L), ids(third));
        assertNull(third.nextCursor());
        verify(keyword, times(1)).firstBatch(any());
        verify(vector, times(1)).firstBatch(any());
    }

    @Test
    void laterPagesRecheckVisibilityAndRefill() {
        CursorPage<ContentSummary, String> first = search.search(context(null, false, 2));
        blocked.add("2");
        CursorPage<ContentSummary, String> second = search.search(context(first.nextCursor(), false, 2));
        assertEquals(List.of(3L, 7L), ids(second));
        assertEquals(List.of(8L), ids(search.search(context(second.nextCursor(), false, 2))));
    }

    @Test
    void doesNotRefillTheEntirePoolWhenEnoughVisibleItemsRemainForTheFirstPage() throws Exception {
        blocked.add("2");
        blocked.add("3");
        CursorPage<ContentSummary, String> first = search.search(context(null, false, 2));
        assertEquals(2, first.items().size());
        verify(keyword, never()).nextBatch(any(), any(), anyInt());
    }

    @Test
    void latestUsesOnlyKeywordPaginationFromTheFirstRequest() {
        CursorPage<ContentSummary, String> first = search.search(context(null, true, 2));
        assertEquals(List.of(1L, 2L), ids(first));
        assertEquals(List.of(3L, 4L), ids(search.search(context(first.nextCursor(), true, 2))));
        verifyNoInteractions(vector);
        verify(keyword, never()).firstBatch(any());
    }

    @Test
    void exhaustedKeywordCandidatesDoNotCreateAnUnnecessaryNextPage() {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of(
                "1",
                "2"))));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of())));
        CursorPage<ContentSummary, String> page = search.search(context(null, false, 2));
        assertEquals(List.of(1L, 2L), ids(page));
        assertNull(page.nextCursor());
    }

    @Test
    void failedInitialKeywordRouteCanBeRetriedOnTheNextRequest() {
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.failedFuture(new IOException("ES down")));
        CursorPage<ContentSummary, String> first = search.search(context(null, false, 2));
        assertEquals(List.of(4L, 6L), ids(first));
        assertEquals(List.of(1L, 2L), ids(search.search(context(first.nextCursor(), false, 2))));
    }

    @Test
    void largeFirstBatchIsConfigurableAndDoesNotLimitFurtherKeywordPages() {
        properties.setFirstPageSize(30);
        keywordIds = LongStream.rangeClosed(1, 130).mapToObj(Long::toString).toList();
        when(keyword.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(
                new RecallBatch(keywordIds.subList(0, 40), position(false, "40"))));
        when(vector.firstBatch(any())).thenReturn(CompletableFuture.completedFuture(RecallBatch.exhausted(List.of())));
        CursorPage<ContentSummary, String> page = search.search(context(null, false, 10));
        assertEquals(30, page.items().size());
        List<Long> displayed = new ArrayList<>(ids(page));
        while (page.nextCursor() != null) {
            page = search.search(context(page.nextCursor(), false, 10));
            displayed.addAll(ids(page));
        }
        assertEquals(LongStream.rangeClosed(1, 130).boxed().toList(), displayed);
        verify(vector, times(1)).firstBatch(any());
    }
}

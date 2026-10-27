package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.domain.dto.*;
import cn.diaojiahong.myyoutube.search.recall.RecallPipeline;
import cn.diaojiahong.myyoutube.search.recall.SearchContentSummaryClient;
import cn.diaojiahong.myyoutube.search.recall.TagSearchPage;
import cn.diaojiahong.myyoutube.common.page.CursorPage;

import cn.diaojiahong.myyoutube.search.service.impl.SearchServiceImpl;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.*;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.elasticsearch.core.*;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentSearchAccessTest {
    final SearchContentSummaryClient summaries = mock(SearchContentSummaryClient.class);
    final ElasticsearchClient client = mock(ElasticsearchClient.class);
    final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
    final RecallPipeline recall = mock(RecallPipeline.class);
    final TagSearchPage tags = mock(TagSearchPage.class);
    final FollowPort follows = mock(FollowPort.class);
    final cn.diaojiahong.myyoutube.search.hot.HotSearchService hot = mock(cn.diaojiahong.myyoutube.search.hot.HotSearchService.class);
    final SearchServiceImpl search = new SearchServiceImpl(operations,follows,mock(ContentInternalPort.class),
            summaries,mock(UserPort.class),mock(S3StorageUtil.class),hot,client,recall,tags);

    @BeforeEach void user() {
        UserContext.setUser("7");
        when(recall.execute(any(), any())).thenAnswer(call -> {
            Function<List<String>, List<String>> readable = call.getArgument(1);
            return readable.apply(recall.execute(call.getArgument(0)));
        });
    }
    @AfterEach void clear() { UserContext.removeUser(); }

    ContentSummary summary(long id) {
        var result = new ContentSummary(); result.setId(id); result.setTitle("标题"+id);
        result.setAuthorId(7L); result.setType("dynamic"); return result;
    }

    @Test void recordsOnlySuccessfulNonemptyFirstPages() {
        when(recall.execute(any())).thenReturn(List.of("1"));
        when(summaries.listByIdsOrdered(any())).thenReturn(List.of(summary(1)));
        var query = new SearchVideoPageQuery(); query.setKeyword("  搜索词  ");
        search.searchVideo(query);
        verify(hot).record(7L, "  搜索词  ");
        clearInvocations(hot); query.setPageNo(2); search.searchVideo(query); verifyNoInteractions(hot);
        query.setPageNo(1); when(summaries.listByIdsOrdered(any())).thenReturn(List.of());
        search.searchVideo(query); verifyNoInteractions(hot);
        when(recall.execute(any())).thenThrow(new IllegalStateException("search failed"));
        assertThrows(IllegalStateException.class, () -> search.searchVideo(query)); verifyNoInteractions(hot);
    }

    @Test void hotEndpointDelegatesToTheNewSnapshot() {
        when(hot.hot()).thenReturn(List.of("组名")); assertEquals(List.of("组名"), search.hot());
    }

    @Test void keywordResultsRecheckPublicStateBeforePagination() {
        when(recall.execute(any())).thenReturn(List.of("1","2","3"));
        when(summaries.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(List.of("1","2","3"),null)))
                .thenReturn(List.of(summary(2),summary(3)));
        var query = new SearchVideoPageQuery(); query.setPageNo(2); query.setPageSize(1);
        var page = search.searchVideo(query);
        assertEquals(2,page.getTotal()); assertEquals(2,page.getPages());
        assertEquals(List.of("3"),page.getList().stream().map(s -> s.getId()).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allSortsReuseRequiredFollowSnapshotForRecallAndResponse(boolean latest) {
        when(follows.getFollowIds(7L)).thenReturn(List.of(7L));
        when(recall.execute(any())).thenAnswer(call -> {
            cn.diaojiahong.myyoutube.search.recall.SearchContext ctx = call.getArgument(0);
            assertEquals(List.of(7L), ctx.getFollowIds());
            return List.of("1", "2");
        });
        var other = summary(2); other.setAuthorId(8L);
        when(summaries.listByIdsOrdered(any())).thenReturn(List.of(summary(1), other));
        var query = new SearchVideoPageQuery(); query.setSortByLatest(latest);
        var page = search.searchVideo(query);
        assertEquals(List.of(true, false), page.getList().stream().map(item -> item.getIsFollow()).toList());
        verify(follows, times(1)).getFollowIds(7L);
    }

    @Test void emptyFollowListExplicitlyMarksAuthorsAsNotFollowed() {
        when(follows.getFollowIds(7L)).thenReturn(List.of());
        when(recall.execute(any())).thenReturn(List.of("1"));
        when(summaries.listByIdsOrdered(any())).thenReturn(List.of(summary(1)));
        var page = search.searchVideo(new SearchVideoPageQuery());
        assertEquals(Boolean.FALSE, page.getList().get(0).getIsFollow());
        verify(follows, times(1)).getFollowIds(7L);
    }

    @Test void failedFollowLookupFailsSearchBeforeRecall() {
        var failure = new IllegalStateException("follow unavailable");
        when(follows.getFollowIds(7L)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> search.searchVideo(new SearchVideoPageQuery())));
        verify(follows, times(1)).getFollowIds(7L);
        verifyNoInteractions(recall, summaries);
    }

    @Test void nullFollowResponseIsNotTreatedAsEmpty() {
        when(follows.getFollowIds(7L)).thenReturn(null);
        assertThrows(NullPointerException.class, () -> search.searchVideo(new SearchVideoPageQuery()));
        verifyNoInteractions(recall, summaries);
    }

    @Test
    @Timeout(5)
    void slowFollowLookupMustCompleteBeforeRecallAndResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(follows.getFollowIds(7L)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return List.of(7L);
        });
        when(recall.execute(any())).thenReturn(List.of("1"));
        when(summaries.listByIdsOrdered(any())).thenReturn(List.of(summary(1)));
        var caller = Executors.newSingleThreadExecutor();
        try {
            var response = caller.submit(() -> {
                UserContext.setUser("7");
                try { return search.searchVideo(new SearchVideoPageQuery()); }
                finally { UserContext.removeUser(); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertFalse(response.isDone());
            verifyNoInteractions(recall, summaries);
            release.countDown();
            assertEquals(Boolean.TRUE, response.get(2, TimeUnit.SECONDS).getList().get(0).getIsFollow());
            verify(follows, times(1)).getFollowIds(7L);
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test void tagResultsUseCursorPage() {
        when(tags.search(any(), isNull(), eq(10))).thenReturn(new CursorPage<>(List.of(summary(12)), "next"));
        var query = new SearchVideoByTagPageQuery(); query.setPageSize(10);
        var result = search.searchByTag(query);
        assertEquals("next", result.nextCursor());
        assertEquals(List.of("12"), result.items().stream().map(s -> s.getId()).toList());
        assertEquals(Boolean.FALSE, result.items().get(0).getIsFollow());
        verifyNoInteractions(hot);
        verify(operations, never()).search(any(NativeQuery.class), eq(ContentDoc.class));
    }

    @Test void pagesBeyondTheBoundedPoolReturnEmptyWithoutOverflowingTheOffset() {
        when(recall.execute(any())).thenReturn(List.of("1"));
        when(summaries.listByIdsOrdered(any())).thenReturn(List.of(summary(1)));
        var keyword = new SearchVideoPageQuery(); keyword.setPageNo(Integer.MAX_VALUE); keyword.setPageSize(100);
        assertTrue(search.searchVideo(keyword).getList().isEmpty());
    }

    @Test void stalePrivateContentCannotLeakThroughCompletionSuggestions() throws Exception {
        var content = response("video_suggest",List.of(option("1","公开标题"),option("2","已私密标题"),option("3","已删除标题")));
        var user = response("user_suggest",List.of(option("7","公开用户")));
        when(client.search(any(Function.class),eq(Void.class))).thenReturn(content,user);
        when(summaries.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(List.of("1","2","3"),null)))
                .thenReturn(List.of(summary(1)));
        assertEquals(List.of("公开标题","公开用户"),search.prompt("公"));
    }

    private static CompletionSuggestOption<Void> option(String id,String text) {
        return CompletionSuggestOption.of(o -> o.id(id).text(text));
    }
    private static SearchResponse<Void> response(String name,List<CompletionSuggestOption<Void>> options) {
        Suggestion<Void> suggestion = Suggestion.of(s -> s.completion(c -> c.text("公").offset(0).length(1).options(options)));
        return new SearchResponse.Builder<Void>().took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0)).hits(h -> h.hits(List.of()))
                .suggest(name,List.of(suggestion)).build();
    }
}

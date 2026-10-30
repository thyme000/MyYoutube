package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.hot.HotSearchService;
import cn.diaojiahong.myyoutube.search.recall.ContentSearchPage;
import cn.diaojiahong.myyoutube.search.recall.SearchContentSummaryClient;
import cn.diaojiahong.myyoutube.search.recall.SearchContext;
import cn.diaojiahong.myyoutube.search.service.impl.SearchServiceImpl;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.CompletionSuggestOption;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchVideoVO;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentSearchAccessTest {
    final SearchContentSummaryClient summaries = mock(SearchContentSummaryClient.class);

    final ElasticsearchClient client = mock(ElasticsearchClient.class);

    final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);

    final ContentSearchPage pages = mock(ContentSearchPage.class);

    final FollowPort follows = mock(FollowPort.class);

    final HotSearchService hot = mock(HotSearchService.class);

    final SearchServiceImpl search = new SearchServiceImpl(operations, follows, mock(ContentInternalPort.class),
            summaries, mock(UserPort.class), mock(S3StorageUtil.class), hot, client, pages);

    @BeforeEach
    void user() {
        UserContext.setUser("7");
    }

    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    ContentSummary summary(long id) {
        ContentSummary result = new ContentSummary();
        result.setId(id);
        result.setTitle("标题" + id);
        result.setAuthorId(7L);
        result.setType("dynamic");
        return result;
    }

    @Test
    void recordsOnlySuccessfulNonemptyFirstRequests() {
        when(pages.search(any())).thenReturn(new CursorPage<>(List.of(summary(1)), "next"));
        ContentSearchCursorQuery query = new ContentSearchCursorQuery();
        query.setKeyword("  搜索词  ");
        search.searchVideo(query);
        verify(hot).record(7L, "  搜索词  ");

        clearInvocations(hot);
        query.setCursor("next");
        search.searchVideo(query);
        verifyNoInteractions(hot);

        query.setCursor(null);
        when(pages.search(any())).thenReturn(new CursorPage<>(List.of(), null));
        search.searchVideo(query);
        verifyNoInteractions(hot);
        when(pages.search(any())).thenThrow(new IllegalStateException("search failed"));
        assertThrows(IllegalStateException.class, () -> search.searchVideo(query));
        verifyNoInteractions(hot);
    }

    @Test
    void hotEndpointDelegatesToSnapshot() {
        when(hot.hot()).thenReturn(List.of("组名"));
        assertEquals(List.of("组名"), search.hot());
    }

    @Test
    void contentResultsForwardTheCursorWithoutApplyingAnOffset() {
        when(pages.search(any())).thenReturn(new CursorPage<>(List.of(summary(3), summary(2)), "next"));
        CursorPage<?, String> result = search.searchVideo(new ContentSearchCursorQuery());
        assertEquals("next", result.nextCursor());
        assertEquals(2, result.items().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allSortsShareTheRequiredFollowSnapshot(boolean latest) {
        when(follows.getFollowIds(7L)).thenReturn(List.of(7L));
        ContentSummary other = summary(2);
        other.setAuthorId(8L);
        when(pages.search(any())).thenAnswer(call -> {
            SearchContext context = call.getArgument(0);
            assertEquals(List.of(7L), context.getFollowIds());
            return new CursorPage<>(List.of(summary(1), other), null);
        });
        ContentSearchCursorQuery query = new ContentSearchCursorQuery();
        query.setSortByLatest(latest);
        assertEquals(
                List.of(true, false),
                search.searchVideo(query).items().stream().map(item -> item.getIsFollow()).toList());
        verify(follows, times(1)).getFollowIds(7L);
    }

    @Test
    void failedOrMissingFollowLookupFailsBeforeSearch() {
        IllegalStateException failure = new IllegalStateException("follow unavailable");
        when(follows.getFollowIds(7L)).thenThrow(failure);
        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> search.searchVideo(new ContentSearchCursorQuery())));
        doReturn(null).when(follows).getFollowIds(7L);
        assertThrows(NullPointerException.class, () -> search.searchVideo(new ContentSearchCursorQuery()));
        verifyNoInteractions(pages, summaries);
    }

    @Test @Timeout(5)
    void slowFollowLookupCompletesBeforeStartingSearch() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(follows.getFollowIds(7L)).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return List.of(7L);
        });
        when(pages.search(any())).thenReturn(new CursorPage<>(List.of(summary(1)), null));
        ThreadPoolTaskExecutor caller = new ThreadPoolTaskExecutor();
        caller.initialize();
        try {
            Future<CursorPage<SearchVideoVO, String>> response = caller.submit(() -> {
                UserContext.setUser("7");
                try {
                    return search.searchVideo(new ContentSearchCursorQuery());
                } finally {
                    UserContext.removeUser();
                }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertFalse(response.isDone());
            verifyNoInteractions(pages);
            release.countDown();
            assertEquals(Boolean.TRUE, response.get(2, TimeUnit.SECONDS).items().get(0).getIsFollow());
        } finally {
            release.countDown();
            caller.shutdown();
        }
    }

    @Test
    void tagResultsUseCursorPage() {
        when(pages.search(any())).thenAnswer(call -> {
            SearchContext context = call.getArgument(0);
            assertTrue(context.isTagSearch());
            assertEquals("previous", context.getQuery().getCursor());
            assertEquals(20, context.getQuery().getPageSize());
            return new CursorPage<>(List.of(summary(12)), "next");
        });
        TagSearchCursorQuery query = new TagSearchCursorQuery();
        query.setCursor("previous");
        query.setPageSize(20);
        CursorPage<?, String> result = search.searchByTag(query);
        assertEquals("next", result.nextCursor());
        assertEquals(1, result.items().size());
        verifyNoInteractions(hot);
    }

    @Test
    void stalePrivateContentCannotLeakThroughCompletionSuggestions() throws Exception {
        SearchResponse<Void> content = response("video_suggest", List.of(option("1", "公开标题"), option("2", "已私密标题")));
        SearchResponse<Void> user = response("user_suggest", List.of(option("7", "公开用户")));
        when(client.search(any(Function.class), eq(Void.class))).thenReturn(content, user);
        when(summaries.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(List.of("1", "2"), null)))
                .thenReturn(List.of(summary(1)));
        assertEquals(List.of("公开标题", "公开用户"), search.prompt("公"));
    }

    private static CompletionSuggestOption<Void> option(String id, String text) {
        return CompletionSuggestOption.of(option -> option.id(id).text(text));
    }

    private static SearchResponse<Void> response(String name, List<CompletionSuggestOption<Void>> options) {
        Suggestion<Void> suggestion = Suggestion.of(s -> s.completion(c -> c.text("公").offset(0).length(1).options(options)));
        return new SearchResponse.Builder<Void>().took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0)).hits(h -> h.hits(List.of()))
                .suggest(name, List.of(suggestion)).build();
    }
}

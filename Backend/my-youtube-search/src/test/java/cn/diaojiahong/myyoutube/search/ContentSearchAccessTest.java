package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.domain.dto.*;
import cn.diaojiahong.myyoutube.search.recall.RecallPipeline;
import cn.diaojiahong.myyoutube.search.service.impl.SearchServiceImpl;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.*;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.*;
import org.junit.jupiter.api.*;
import org.springframework.data.elasticsearch.core.*;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentSearchAccessTest {
    final ContentSummaryPort summaries = mock(ContentSummaryPort.class);
    final ElasticsearchClient client = mock(ElasticsearchClient.class);
    final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
    final RecallPipeline recall = mock(RecallPipeline.class);
    final SearchServiceImpl search = new SearchServiceImpl(operations,mock(FollowPort.class),mock(ContentInternalPort.class),
            summaries,mock(UserPort.class),mock(S3StorageUtil.class),mock(StringRedisTemplate.class),client,recall);

    @BeforeEach void user() { UserContext.setUser("7"); }
    @AfterEach void clear() { UserContext.removeUser(); }

    ContentSummary summary(long id) {
        var result = new ContentSummary(); result.setId(id); result.setTitle("标题"+id);
        result.setAuthorId(7L); result.setType("dynamic"); return result;
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

    @Test void tagResultsDoNotApplyPageOffsetToAnAlreadyPaginatedEsResponse() {
        SearchHits<ContentDoc> hits = mock(SearchHits.class);
        SearchHit<ContentDoc> hit = mock(SearchHit.class);
        var doc = new ContentDoc(); doc.setId(12L);
        when(hit.getContent()).thenReturn(doc); when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(hits.getTotalHits()).thenReturn(11L);
        when(operations.search(any(NativeQuery.class),eq(ContentDoc.class))).thenReturn(hits);
        when(summaries.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(List.of("12"),null)))
                .thenReturn(List.of(summary(12)));
        var query = new SearchVideoByTagPageQuery(); query.setPageNo(2); query.setPageSize(10);
        var result = search.searchByTag(query);
        assertEquals(11,result.getTotal()); assertEquals(List.of("12"),result.getList().stream().map(s -> s.getId()).toList());
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

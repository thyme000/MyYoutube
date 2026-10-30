package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import cn.diaojiahong.myyoutube.common.concurrent.AsyncTimeout;

/**
 * 关键词召回路: BM25 多字段匹配 + 关注/播放/获赞加权, 空关键词退化为热门加权流
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class KeywordRecallStrategy implements RecallStrategy {

    private final RecallSearchClient searchClient;
    private final VectorProperties vectorProperties;
    private final RecallRanking ranking;

    @Override
    public boolean shouldRun(SearchContext ctx) {
        return true;
    }

    @Override
    public boolean supportsPaging() {
        return true;
    }

    @Override
    @AsyncTimeout("keyword-recall")
    public CompletableFuture<RecallBatch> firstBatch(SearchContext ctx) {
        try {
            return CompletableFuture.completedFuture(load(ctx, List.of(), vectorProperties.getRecallSize() * 2));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    // 续查基于实时 _score，跨请求可能重排；重复由前端按 id 去重处理
    @Override
    public RecallBatch nextBatch(SearchContext ctx, RecallBatch previous, int size) throws Exception {
        return load(ctx, previous.nextCursor(), size);
    }

    public RecallBatch load(SearchContext ctx, List<FieldValue> cursor,
                             int size) throws Exception {
        return load(ctx, cursor, size, Set.of());
    }

    public RecallBatch load(SearchContext ctx, List<FieldValue> cursor, int size, Set<String> excluded) throws Exception {
        Query relevance = Query.of(q -> q.bool(b -> {
            b.must(m -> m.term(t -> t.field("status").value("published")));
            b.must(m -> m.term(t -> t.field("visibility").value("public")));
            if (!excluded.isEmpty()) b.mustNot(exclude -> exclude.ids(ids -> ids.values(List.copyOf(excluded))));
            if (ctx.getCategory() != null) {
                b.must(m -> m.term(t -> t.field("category").value(ctx.getCategory())));
            }
            if (!ctx.getKeyword().isEmpty()) {
                b.must(m -> m.multiMatch(mm -> mm.query(ctx.getKeyword())
                        .fields(ctx.isTagSearch() ? List.of("title^2", "tagNames^6", "description^1") : ranking.keywordFields())
                        .fuzziness("AUTO").minimumShouldMatch(ctx.isTagSearch() ? "60%" : "40%")));
            }
            return b;
        }));
        SearchRequest.Builder query = new SearchRequest.Builder().index("content")
                .query(ctx.isTagSearch() ? ranking.tag(relevance, ctx) : ranking.keyword(relevance, ctx))
                .size(size + 1)
                .source(source -> source.fetch(false));
        if (!ctx.isSortByLatest()) query.sort(s -> s.score(score -> score.order(SortOrder.Desc)));
        query.sort(s -> s.field(field -> field.field("createTime").order(SortOrder.Desc)))
                .sort(s -> s.field(field -> field.field("id").order(SortOrder.Desc)));
        if (!cursor.isEmpty()) query.searchAfter(cursor);
        return searchClient.page(query.build(), size);
    }
}

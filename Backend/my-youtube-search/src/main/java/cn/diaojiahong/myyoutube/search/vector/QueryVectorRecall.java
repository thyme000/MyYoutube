package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import cn.diaojiahong.myyoutube.search.hot.HotSearchService;
import cn.diaojiahong.myyoutube.search.recall.RecallSearchClient;
import cn.diaojiahong.myyoutube.search.recall.RecallRanking;
import cn.diaojiahong.myyoutube.search.recall.SearchContext;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import feign.Request;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static cn.diaojiahong.myyoutube.search.recall.RecallSearchClient.remainingMillis;

@Service
@RequiredArgsConstructor
public class QueryVectorRecall {
    private final QueryEmbeddingPort embeddings;
    private final RecallSearchClient searchClient;
    private final VectorProperties properties;
    private final RecallRanking ranking;
    private final HotSearchService hotSearch;

    public List<String> recall(SearchContext ctx, long deadlineNanos) throws Exception {
        var cached = hotSearch.cachedGroup(ctx.getKeyword(), deadlineNanos);
        ContentVectorService.QueryVector vector = null;
        if (cached != null && ctx.getKeyword().equals(cached.name())) {
            try {
                long budget = Math.min(200, remainingMillis(deadlineNanos));
                var info = embeddings.info(new Request.Options(budget, TimeUnit.MILLISECONDS, budget, TimeUnit.MILLISECONDS, true));
                if (info != null && cached.valid(info.version(), info.dims())) {
                    vector = new ContentVectorService.QueryVector(cached.vector(), cached.version());
                }
            } catch (Exception unavailable) {
                remainingMillis(deadlineNanos);
            }
        }
        if (vector == null) vector = generate(ctx, deadlineNanos);
        if (vector == null) return List.of();
        remainingMillis(deadlineNanos);
        ContentVectorService.QueryVector queryVector = vector;
        // 先扩展语义候选，再按业务加权取最终召回深度；加权不会影响 HNSW 候选遍历。
        int candidates = properties.getRecallSize() * 2;
        Query knn = Query.of(q -> q.knn(k -> k.field("vector").queryVector(queryVector.vector())
                .k(candidates).numCandidates(Math.max(candidates * 3, 100))
                .filter(ContentVectorService.knnFilter(queryVector, ctx.getCategory()))));
        SearchRequest request = SearchRequest.of(s -> s.index("content").size(properties.getRecallSize())
                .source(source -> source.fetch(false)).query(ranking.vector(knn, ctx)));
        return searchClient.search(request, deadlineNanos);
    }

    private ContentVectorService.QueryVector generate(SearchContext ctx, long deadlineNanos) throws Exception {
        long remaining = remainingMillis(deadlineNanos);
        var response = embeddings.embed(new QueryEmbeddingPort.Query(ctx.getKeyword(), System.currentTimeMillis() + remaining),
                new Request.Options(remaining, TimeUnit.MILLISECONDS, remaining, TimeUnit.MILLISECONDS, true));
        remainingMillis(deadlineNanos);
        if (response == null || response.getUnavailableReason() != null || response.getItems() == null
                || response.getItems().size() != 1 || response.getVersion() == null || response.getVersion().isBlank()) return null;
        var item = response.getItems().get(0);
        if (item.index() != 0 || item.error() != null || item.vector() == null || item.vector().isEmpty()
                || response.getDims() == null || response.getDims() != item.vector().size()
                || item.vector().stream().anyMatch(value -> value == null || !Float.isFinite(value))) return null;
        return new ContentVectorService.QueryVector(item.vector(), response.getVersion());
    }
}

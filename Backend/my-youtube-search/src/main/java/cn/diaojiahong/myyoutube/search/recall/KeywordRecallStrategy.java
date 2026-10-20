package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词召回路: BM25 多字段匹配 + 关注/播放/获赞加权, 空关键词退化为热门加权流
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class KeywordRecallStrategy implements RecallStrategy {

    private final RecallSearchClient searchClient;
    private final VectorProperties vectorProperties;

    @Override
    public boolean shouldRun(SearchContext ctx) {
        return true;
    }

    @Override
    public boolean needsFollowIds() { return true; }

    @Override
    public List<String> recall(SearchContext ctx, long deadlineNanos) throws Exception {
        // 任一路可能单独返回，关键词保留原单路的召回深度。
        int size = vectorProperties.getRecallSize() * 2;
        SearchRequest query = new SearchRequest.Builder().index("content")
                .query(q -> q.functionScore(fs -> fs
                        .query(inner -> inner.bool(b -> {
                            b.must(m -> m.term(t -> t.field("status").value("published")));
                            b.must(m -> m.term(t -> t.field("visibility").value("public")));
                            if (ctx.getCategory() != null) {
                                b.must(m -> m.term(t -> t.field("category").value(ctx.getCategory())));
                            }
                            if (!ctx.getKeyword().isEmpty()) {
                                b.must(m -> m.multiMatch(mm -> mm
                                        .query(ctx.getKeyword())
                                        .fields("title^5", "tagNames^4", "authorName^2", "description^3")
                                        .fuzziness("AUTO")
                                        .minimumShouldMatch("40%")
                                ));
                            }
                            return b;
                        }))
                        .functions(boostFunctions(ctx))
                        .scoreMode(FunctionScoreMode.Sum)
                        .boostMode(FunctionBoostMode.Sum)
                ))
                .sort(s -> s.score(score -> score.order(SortOrder.Desc)))
                .sort(s -> s.field(field -> field.field("createTime").order(SortOrder.Desc)))
                .size(size)
                .source(source -> source.fetch(false))
                .build();
        return searchClient.search(query, deadlineNanos);
    }

    /** 关注作者加权 + 播放量/获赞对数加权 */
    private List<FunctionScore> boostFunctions(SearchContext ctx) {
        List<FunctionScore> functions = new ArrayList<>();
        for (Long fid : ctx.getFollowIds()) {
            functions.add(FunctionScore.of(f -> f
                    .filter(fq -> fq.term(t -> t.field("authorId").value(fid)))
                    .weight(5.0)));
        }
        functions.add(fieldValueFactor("playCount"));
        functions.add(fieldValueFactor("likeCount"));
        return functions;
    }

    private FunctionScore fieldValueFactor(String field) {
        return FunctionScore.of(f -> f.fieldValueFactor(fvf -> fvf
                .field(field)
                .modifier(FieldValueFactorModifier.Log1p)
                .missing(0.0)));
    }
}

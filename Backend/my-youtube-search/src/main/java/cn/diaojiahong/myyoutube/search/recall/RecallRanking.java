package cn.diaojiahong.myyoutube.search.recall;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RecallRanking {
    private final RecallRankingProperties properties;

    public List<String> keywordFields() { return properties.getFields().boostedFields(); }

    public Query keyword(Query query, SearchContext ctx) { return apply(query, ctx, properties.getKeyword()); }
    public Query vector(Query query, SearchContext ctx) { return apply(query, ctx, properties.getVector()); }

    private Query apply(Query query, SearchContext ctx, RecallRankingProperties.Weights weights) {
        if (ctx.isSortByLatest()) return query;
        List<FunctionScore> functions = new ArrayList<>();
        // 保底倍率为 1；未关注、零热度或缺少计数字段都不降低相关性分。
        functions.add(FunctionScore.of(f -> f.weight(1.0)));
        if (weights.getFollow() > 0 && !ctx.getFollowIds().isEmpty()) {
            functions.add(FunctionScore.of(f -> f
                    .filter(q -> q.terms(t -> t.field("authorId").terms(v -> v
                            .value(ctx.getFollowIds().stream().map(FieldValue::of).toList()))))
                    .weight(weights.getFollow())));
        }
        if (weights.getPlay() > 0) functions.add(counter("playCount", weights.getPlay()));
        if (weights.getLike() > 0) functions.add(counter("likeCount", weights.getLike()));
        return Query.of(q -> q.functionScore(f -> f.query(query).functions(functions)
                .scoreMode(FunctionScoreMode.Sum).boostMode(FunctionBoostMode.Multiply)
                .maxBoost(weights.getMaxMultiplier())));
    }

    private FunctionScore counter(String field, double weight) {
        return FunctionScore.of(f -> f.weight(weight).fieldValueFactor(v -> v.field(field)
                .modifier(FieldValueFactorModifier.Log1p).missing(0.0)));
    }
}

package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
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

    private final ElasticsearchOperations elasticsearchOperations;
    private final VectorProperties vectorProperties;

    @Override
    public boolean shouldRun(SearchContext ctx) {
        return true;
    }

    @Override
    public List<String> recall(SearchContext ctx) {
        // 纯关键词单路时加大召回深度, 兜住融合结果上的应用层深分页
        int size = ctx.getQueryVector() == null
                ? vectorProperties.getRecallSize() * 2
                : vectorProperties.getRecallSize();
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.functionScore(fs -> fs
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
                .withSort(Sort.by(Sort.Order.desc("_score"), Sort.Order.desc("createTime")))
                .withPageable(PageRequest.of(0, size))
                .build();
        return elasticsearchOperations.search(query, ContentDoc.class).getSearchHits().stream()
                .map(hit -> hit.getContent().getId().toString())
                .toList();
    }

    /** 关注作者加权 + 播放量/获赞对数加权, followIds 由 prep 统一解析挂 ctx */
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

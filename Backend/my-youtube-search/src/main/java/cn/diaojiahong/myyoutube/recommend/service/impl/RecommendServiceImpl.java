package cn.diaojiahong.myyoutube.recommend.service.impl;


import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.recommend.service.IRecommendService;
import cn.diaojiahong.myyoutube.video.api.PreferencePort;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements IRecommendService {

    /** 热度路的时间衰减参数: 1 天内不衰减, 之后每 7 天衰减一半 */
    private static final String HOT_DECAY_SCALE = "7d";
    private static final String HOT_DECAY_OFFSET = "1d";

    /** 推荐召回封顶: 深翻页时 from+size 不得越过 ES max_result_window(默认 10000) */
    private static final int RECALL_CAP = 1000;

    private final PreferencePort preferencePort;
    private final ElasticsearchOperations elasticsearchOperations;
    private final StringRedisTemplate redisTemplate;
    private final FollowPort followPort;

    /**
     * 推荐内容: tag 偏好路 + 热度路 合流去重。
     * @param userId 用户ID
     * @param category 分类
     * @param page 页码
     * @param size 每页数量
     * @return 推荐内容ID列表
     */
    @Override
    public List<Long> recommend(Long userId, String category, int page, int size) {
        // 客户端分页参数不可信: page<1 会让 PageRequest.of 抛参数异常
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        Set<String> excludeIds = excludedContentIds(userId);

        // tag 偏好路: 无偏好(新用户)时整路跳过, 由热度路兜底
        List<Long> tagRoute = List.of();
        List<PreferencePort.TagPreferenceSummary> preferences = preferencePort.topPreferences(userId, 20);
        if (!preferences.isEmpty()) {
            // 召回统一从第 0 页取前 min(需求量, 封顶) 条, 由切片负责分页, ES 窗口不随页码增长
            tagRoute = searchIds(tagRouteQuery(userId, category, preferences, excludeIds,
                    PageRequest.of(0, Math.min(safeSize * safePage, RECALL_CAP))));
        }

        // 热度路: content 互动计数 + 时间衰减, 零标签的动态也天然进池
        List<Long> hotRoute = searchIds(hotRouteQuery(userId, category, excludeIds,
                PageRequest.of(0, Math.min(safeSize * safePage, RECALL_CAP))));

        // 合流去重: tag 路优先, 热度路补足, 按窗口切片
        LinkedHashSet<Long> merged = new LinkedHashSet<>(tagRoute);
        merged.addAll(hotRoute);
        List<Long> ordered = new ArrayList<>(merged);
        int from = (safePage - 1) * safeSize;
        if (from >= ordered.size()) {
            return List.of();
        }
        return ordered.subList(from, Math.min(from + safeSize, ordered.size()));
    }

    /**
     * tag 偏好路: 标签偏好权重 + 关注作者加权
     */
    private NativeQuery tagRouteQuery(Long userId, String category,
                                      List<PreferencePort.TagPreferenceSummary> preferences,
                                      Set<String> excludeIds, Pageable pageable) {
        List<FunctionScore> functions = new ArrayList<>();
        // 关注作者加权(个性化信号之一)
        for (Long fid : followPort.getFollowIds(userId)) {
            functions.add(FunctionScore.of(f -> f
                    .filter(fq -> fq.term(t -> t.field("authorId").value(fid)))
                    .weight(50.0)
            ));
        }
        // 标签偏好权重
        for (PreferencePort.TagPreferenceSummary p : preferences) {
            functions.add(FunctionScore.of(f -> f
                    .filter(fq -> fq.term(t -> t.field("tagIds").value(p.getTagId())))
                    .weight(p.getScore() > 0 ? (double) p.getScore() : 0) // es里不允许权重为负数
            ));
        }

        return NativeQuery.builder()
                .withQuery(q -> q.functionScore(fs -> fs
                        .query(inner -> inner.bool(b -> {
                            applyCommonFilters(b, userId, category, excludeIds);
                            return b;
                        }))
                        .functions(functions)
                        .scoreMode(FunctionScoreMode.Sum)
                        .boostMode(FunctionBoostMode.Replace)
                ))
                .withPageable(pageable)
                .build();
    }

    /**
     * 热度路: likeCount/playCount 计分 + 发布时间高斯衰减
     */
    private NativeQuery hotRouteQuery(Long userId, String category,
                                      Set<String> excludeIds, Pageable pageable) {
        return NativeQuery.builder()
                .withQuery(q -> q.functionScore(fs -> fs
                        .query(inner -> inner.bool(b -> {
                            applyCommonFilters(b, userId, category, excludeIds);
                            return b;
                        }))
                        .functions(List.of(
                                FunctionScore.of(f -> f
                                        .fieldValueFactor(fvf -> fvf
                                                .field("playCount")
                                                .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                                                .missing(0.0)
                                        )),
                                FunctionScore.of(f -> f
                                        .fieldValueFactor(fvf -> fvf
                                                .field("likeCount")
                                                .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                                                .missing(0.0)
                                        )),
                                FunctionScore.of(f -> f
                                        .gauss(g -> g
                                                .date(d -> d
                                                        .field("createTime")
                                                        .placement(pl -> pl
                                                                .scale(Time.of(t -> t.time(HOT_DECAY_SCALE)))
                                                                .offset(Time.of(t -> t.time(HOT_DECAY_OFFSET)))
                                                                .decay(0.5)
                                                        )
                                                )
                                        ))
                        ))
                        .scoreMode(FunctionScoreMode.Sum)
                        .boostMode(FunctionBoostMode.Replace)
                ))
                .withPageable(pageable)
                .build();
    }

    private void applyCommonFilters(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                                    Long userId, String category, Set<String> excludeIds) {
        b.must(m -> m.term(t -> t.field("status").value("published")));
        if (category != null) {
            b.must(m -> m.term(t -> t.field("category").value(category)));
        }
        if (!excludeIds.isEmpty()) {
            b.mustNot(m -> m.ids(ids -> ids.values(excludeIds.stream().toList())));
        }
        b.mustNot(m -> m.term(t -> t.field("authorId").value(userId)));
        b.must(m -> m.term(t -> t.field("visibility").value("public")));
    }

    private Set<String> excludedContentIds(Long userId) {
        // 看过和踩过的内容不再推荐
        Set<String> viewed = redisTemplate.opsForSet().members(RedisConstants.USER_VIEWED + userId);
        Set<String> disliked = redisTemplate.opsForSet().members(RedisConstants.USER_DISLIKE + userId);
        Set<String> excludeIds = new HashSet<>();
        if (viewed != null) {
            excludeIds.addAll(viewed);
        }
        if (disliked != null) {
            excludeIds.addAll(disliked);
        }
        return excludeIds;
    }

    private List<Long> searchIds(NativeQuery query) {
        SearchHits<ContentDoc> hits = elasticsearchOperations.search(query, ContentDoc.class);
        return hits.getSearchHits().stream()
                .map(h -> h.getContent().getId())
                .toList();
    }

    /**
     * 关注流: 从被关注用户的内容中召回(tag 偏好加权), 动态与视频同池
     */
    @Override
    public List<Long> recommendForFollow(List<Long> followIds, Long userId, int page, int size) {
        // 没有关注的人，直接返回空
        if (followIds == null || followIds.isEmpty()) {
            return List.of();
        }

        List<PreferencePort.TagPreferenceSummary> preferences = preferencePort.topPreferences(userId, 20);

        Pageable pageable = PageRequest.of(Math.max(1, page) - 1, Math.max(1, size));

        Set<String> excludeIds = excludedContentIds(userId);

        // 把 followIds 转为 ES terms 需要的 FieldValue 列表
        List<FieldValue> followFieldValues = followIds.stream()
                .map(FieldValue::of)
                .toList();

        NativeQuery query;

        if (preferences.isEmpty()) {
            // 无偏好 → 只限定关注作者，按发布时间倒序
            query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        b.must(m -> m.term(t -> t.field("status").value("published")));
                        // 只查关注的人的内容
                        b.must(m -> m.terms(t -> t
                                .field("authorId")
                                .terms(tv -> tv.value(followFieldValues))
                        ));
                        if (!excludeIds.isEmpty()) {
                            b.mustNot(m -> m.ids(ids -> ids.values(excludeIds.stream().toList())));
                        }
                        b.must(m -> m.term(t -> t.field("visibility").value("public")));
                        return b;
                    }))
                    .withSort(Sort.by(Sort.Order.desc("createTime")))
                    .withPageable(pageable)
                    .build();
        } else {
            // 有偏好 → 限定关注作者 + 标签偏好打分
            List<FunctionScore> functions = preferences.stream()
                    .map(p -> FunctionScore.of(f -> f
                            .filter(fq -> fq.term(t -> t.field("tagIds").value(p.getTagId())))
                            .weight(p.getScore() > 0 ? (double) p.getScore() : 0)
                    ))
                    .toList();

            query = NativeQuery.builder()
                    .withQuery(q -> q.functionScore(fs -> fs
                            .query(inner -> inner.bool(b -> {
                                b.must(m -> m.term(t -> t.field("status").value("published")));
                                // 只查关注的人的内容
                                b.must(m -> m.terms(t -> t
                                        .field("authorId")
                                        .terms(tv -> tv.value(followFieldValues))
                                ));
                                if (!excludeIds.isEmpty()) {
                                    b.mustNot(m -> m.ids(ids -> ids.values(excludeIds.stream().toList())));
                                }
                                b.must(m -> m.term(t -> t.field("visibility").value("public")));
                                return b;
                            }))
                            .functions(functions)
                            .scoreMode(FunctionScoreMode.Sum)
                            .boostMode(FunctionBoostMode.Replace)
                    ))
                    .withPageable(pageable)
                    .build();
        }

        return searchIds(query);
    }
}

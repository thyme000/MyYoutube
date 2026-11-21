package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;


import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.video.api.CfRecommendPort;
import cn.diaojiahong.myyoutube.user.api.FollowPort;
import cn.diaojiahong.myyoutube.user.api.UserBlockPort;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.recommend.service.IRecommendService;
import cn.diaojiahong.myyoutube.video.api.PreferencePort;
import cn.diaojiahong.myyoutube.video.api.ViewHistoryPort;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements IRecommendService {

    /** 热度路的时间衰减参数: 1 天内不衰减, 之后每 7 天衰减一半 */
    private static final String HOT_DECAY_SCALE = "7d";
    private static final String HOT_DECAY_OFFSET = "1d";

    /** 每路参与融合的候选上限。 */
    private static final int RECALL_CAP = 1000;
    private static final int RECALL_BATCH_SIZE = 500;
    private static final int ES_RESULT_WINDOW = 10_000;
    private static final Sort SCORE_ORDER = Sort.by(Sort.Order.desc("_score"), Sort.Order.desc("id"));
    private static final Sort RECENT_ORDER = Sort.by(Sort.Order.desc("createTime"), Sort.Order.desc("id"));

    /** 融合来源位标记: 一个候选可同时被多路召回 */
    private static final int SOURCE_TAG = 1;
    private static final int SOURCE_HOT = 2;
    private static final int SOURCE_CF = 4;

    private final PreferencePort preferencePort;
    private final ElasticsearchOperations elasticsearchOperations;
    private final FollowPort followPort;
    private final CfRecommendPort cfRecommendPort;
    private final ViewHistoryPort viewHistoryPort;
    private final MeterRegistry metrics;
    private final UserBlockPort blocks;

    /** CF 路开关: 关闭后退回 tag+热度两路 */
    @Value("${my-youtube.recommend.cf.enabled:true}")
    private boolean cfEnabled;

    /** RRF 平滑常数, 越大排名差异越平缓 */
    @Value("${my-youtube.recommend.fusion.k:60}")
    private double rrfK;

    /** 三路 RRF 权重 */
    @Value("${my-youtube.recommend.fusion.weights.tag:1.0}")
    private double weightTag;

    @Value("${my-youtube.recommend.fusion.weights.hot:0.6}")
    private double weightHot;

    @Value("${my-youtube.recommend.fusion.weights.cf:1.0}")
    private double weightCf;

    /**
     * 推荐内容: tag 偏好路 + 热度路 + CF 协同过滤路, RRF 排名融合。
     * @param userId 用户ID
     * @param type 内容类型: null/空=混合流, 非空=独立类型流(三路召回均限定该类型)
     * @param category 分类
     * @param page 页码
     * @param size 每页数量
     * @return 推荐内容ID列表
     */
    @Override
    @ObservedBusiness(BusinessOperation.RECOMMEND)
    public List<Long> recommend(Long userId, String type, String category, int page, int size) {
        // 客户端分页参数不可信: page<1 会让 PageRequest.of 抛参数异常
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        Set<String> dislikedIds = dislikedContentIds(userId);
        int needed = (int) Math.min((long) safePage * safeSize, RECALL_CAP);
        List<FieldValue> blockedAuthors = blocks.blockedAuthors(userId).stream()
                .map(id -> FieldValue.of(id.longValue())).toList();

        // tag 偏好路: 无偏好(新用户)时整路跳过, 由热度路兜底
        List<Long> tagRoute = List.of();
        List<PreferencePort.TagPreferenceSummary> preferences = preferencePort.topPreferences(userId, 20);
        if (!preferences.isEmpty()) {
            NativeQuery query = tagRouteQuery(userId, type, category, preferences, dislikedIds, blockedAuthors);
            tagRoute = recallUnviewed(query, userId, needed);
        }

        // 热度路: content 互动计数 + 时间衰减, 零标签的动态也天然进池
        List<Long> hotRoute = recallUnviewed(hotRouteQuery(userId, type, category, dislikedIds, blockedAuthors), userId, needed);

        // CF 路: 协同过滤扩散候选, 回 ES 过滤失效内容; 失败或无行为时退回两路
        List<Long> cfRoute = List.of();
        String cfAlgorithm = "-";
        if (cfEnabled) {
            try {
                CfRecommendPort.CfCandidates candidates = cfRecommendPort.candidates(
                        new CfRecommendPort.CfCandidatesQuery(userId, type, RECALL_CAP));
                cfAlgorithm = candidates.getAlgorithm();
                cfRoute = filterCfCandidates(candidates.getContentIds(), userId, type, category, dislikedIds, blockedAuthors, needed);
            } catch (Exception e) {
                metrics.counter(MetricNames.RECOMMEND_FALLBACKS, "strategy", "cf").increment();
                log.warn("CF 召回失败, 本轮退回 tag+热度两路 userId={}", userId, e);
            }
        }

        // RRF 排名融合: score = Σ w_route/(k+rank), 不依赖各路分数可比
        Map<Long, Double> fused = new HashMap<>();
        Map<Long, Integer> sources = new HashMap<>();
        addRoute(fused, sources, tagRoute, weightTag, SOURCE_TAG);
        addRoute(fused, sources, hotRoute, weightHot, SOURCE_HOT);
        addRoute(fused, sources, cfRoute, weightCf, SOURCE_CF);

        List<Long> ordered = fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        logFusion(userId, type, safePage, tagRoute, hotRoute, cfRoute, cfAlgorithm, sources, ordered);

        return page(ordered, safePage, safeSize);
    }

    /**
     * CF 候选回 ES 套用统一过滤(published/公开/类型/非已看已踩), 命中者按 CF 原排序保留
     */
    private List<Long> filterCfCandidates(List<Long> candidateIds, Long userId, String type, String category,
                                          Set<String> dislikedIds, List<FieldValue> blockedAuthors, int needed) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return List.of();
        }

        List<String> ids = candidateIds.stream().map(String::valueOf).toList();
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    applyCommonFilters(b, userId, type, category, dislikedIds, blockedAuthors);
                    b.must(m -> m.ids(i -> i.values(ids)));
                    return b;
                }))
                .withPageable(PageRequest.of(0, candidateIds.size()))
                .build();
        Set<Long> alive = new HashSet<>(searchIds(query));
        List<Long> eligible = candidateIds.stream().filter(alive::contains).toList();
        return unviewed(userId, eligible).stream().limit(needed).toList();
    }

    private void addRoute(Map<Long, Double> fused, Map<Long, Integer> sources,
                          List<Long> route, double weight, int sourceBit) {
        for (int i = 0; i < route.size(); i++) {
            long id = route.get(i);
            fused.merge(id, weight / (rrfK + i + 1), Double::sum);
            sources.merge(id, sourceBit, (a, b) -> a | b);
        }
    }

    /**
     * 链路观测: 各路召回条数与融合后列表的来源命中占比
     */
    private void logFusion(Long userId, String type, int page, List<Long> tagRoute, List<Long> hotRoute,
                           List<Long> cfRoute, String cfAlgorithm, Map<Long, Integer> sources, List<Long> ordered) {
        int inTag = 0;
        int inHot = 0;
        int inCf = 0;
        for (Long id : ordered) {
            int mask = sources.getOrDefault(id, 0);
            inTag += (mask & SOURCE_TAG) > 0 ? 1 : 0;
            inHot += (mask & SOURCE_HOT) > 0 ? 1 : 0;
            inCf += (mask & SOURCE_CF) > 0 ? 1 : 0;
        }
        log.debug("推荐融合 userId={} type={} page={} tag路{}条 热度路{}条 CF路{}条(algo={}) 融合后{}条 来源命中 tag={} hot={} cf={}",
                userId, type == null || type.isBlank() ? "mixed" : type, page,
                tagRoute.size(), hotRoute.size(), cfRoute.size(), cfAlgorithm,
                ordered.size(), inTag, inHot, inCf);
    }

    /**
     * tag 偏好路: 标签偏好权重 + 关注作者加权
     */
    private NativeQuery tagRouteQuery(Long userId, String type, String category,
                                      List<PreferencePort.TagPreferenceSummary> preferences,
                                      Set<String> dislikedIds, List<FieldValue> blockedAuthors) {
        List<FunctionScore> functions = new ArrayList<>();
        // 关注作者加权(个性化信号之一)
        for (Long fid : followPort.getFollowIds(userId)) {
            functions.add(FunctionScore.of(f -> f.filter(fq -> fq.term(t -> t.field("authorId").value(fid)))
                    .weight(50.0)
            ));
        }
        // 标签偏好权重
        for (PreferencePort.TagPreferenceSummary p : preferences) {
            functions.add(FunctionScore.of(f -> f.filter(fq -> fq.term(t -> t.field("tagIds").value(p.getTagId())))
                    .weight(p.getScore() > 0 ? (double) p.getScore() : 0)
                    // es里不允许权重为负数
            ));
        }

        return NativeQuery.builder()
                .withQuery(q -> q.functionScore(fs -> fs.query(inner -> inner.bool(b -> {
                            applyCommonFilters(b, userId, type, category, dislikedIds, blockedAuthors);
                            return b;
                        }))
                        .functions(functions)
                        .scoreMode(FunctionScoreMode.Sum)
                        .boostMode(FunctionBoostMode.Replace)
                ))
                .withSort(SCORE_ORDER)
                .build();
    }

    /**
     * 热度路: likeCount/playCount 计分 + 发布时间高斯衰减
     */
    private NativeQuery hotRouteQuery(Long userId, String type, String category, Set<String> dislikedIds, List<FieldValue> blockedAuthors) {
        return NativeQuery.builder()
                .withQuery(q -> q.functionScore(fs -> fs.query(inner -> inner.bool(b -> {
                            applyCommonFilters(b, userId, type, category, dislikedIds, blockedAuthors);
                            return b;
                        }))
                        .functions(List.of(
                                FunctionScore.of(f -> f.fieldValueFactor(fvf -> fvf.field("playCount")
                                                .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                                                .missing(0.0)
                                        )),
                                FunctionScore.of(f -> f.fieldValueFactor(fvf -> fvf.field("likeCount")
                                                .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                                                .missing(0.0)
                                        )),
                                FunctionScore.of(f -> f.gauss(g -> g.date(d -> d.field("createTime")
                                                        .placement(pl -> pl.scale(Time.of(t -> t.time(HOT_DECAY_SCALE)))
                                                                .offset(Time.of(t -> t.time(HOT_DECAY_OFFSET)))
                                                                .decay(0.5)
                                                        )
                                                )
                                        ))
                        ))
                        .scoreMode(FunctionScoreMode.Sum)
                        .boostMode(FunctionBoostMode.Replace)
                ))
                .withSort(SCORE_ORDER)
                .build();
    }

    private void applyCommonFilters(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                                    Long userId, String type, String category, Set<String> dislikedIds, List<FieldValue> blockedAuthors) {
        b.must(m -> m.term(t -> t.field("status").value("published")));
        if (type != null && !type.isBlank()) {
            // 独立类型流: 三路召回统一限定类型
            b.must(m -> m.term(t -> t.field("type").value(type)));
        }
        if (category != null) {
            b.must(m -> m.term(t -> t.field("category").value(category)));
        }
        if (!dislikedIds.isEmpty()) {
            b.mustNot(m -> m.ids(ids -> ids.values(dislikedIds.stream().toList())));
        }
        b.mustNot(m -> m.term(t -> t.field("authorId").value(userId)));
        if (!blockedAuthors.isEmpty()) {
            b.mustNot(m -> m.terms(t -> t.field("authorId").terms(values -> values.value(blockedAuthors))));
        }
        b.must(m -> m.term(t -> t.field("visibility").value("public")));
    }

    private Set<String> dislikedContentIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return preferencePort.activeDislikedIds(userId).stream().map(String::valueOf).collect(Collectors.toSet());
    }

    private List<Long> recallUnviewed(NativeQuery query, Long userId, int needed) {
        List<Long> result = new ArrayList<>();
        // 从榜首累计未看候选，再按客户端页码切片，避免先分页导致后续候选无法补位。
        for (int batch = 0; batch < ES_RESULT_WINDOW / RECALL_BATCH_SIZE && result.size() < needed; batch++) {
            query.setPageable(PageRequest.of(batch, RECALL_BATCH_SIZE));
            List<Long> candidates = searchIds(query);
            List<Long> eligible = unviewed(userId, candidates);
            int count = Math.min(needed - result.size(), eligible.size());
            result.addAll(eligible.subList(0, count));
            if (candidates.size() < RECALL_BATCH_SIZE) {
                break;
            }
        }
        return result;
    }

    private List<Long> unviewed(Long userId, List<Long> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> viewedIds = viewHistoryPort.viewedIds(new ViewHistoryPort.Candidates(userId, candidates));
        Set<Long> viewed = new HashSet<>(viewedIds);
        return candidates.stream().filter(id -> !viewed.contains(id)).toList();
    }

    private List<Long> page(List<Long> ids, int page, int size) {
        long from = (long) (page - 1) * size;
        if (from >= ids.size()) {
            return List.of();
        }
        return ids.subList((int) from, (int) Math.min(from + size, ids.size()));
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
    @ObservedBusiness(BusinessOperation.RECOMMEND_FOLLOW)
    public List<Long> recommendForFollow(List<Long> followIds, Long userId, int page, int size) {
        if (followIds == null || followIds.isEmpty()) {
            return List.of();
        }
        List<Long> blocked = blocks.blockedAuthors(userId);
        List<FieldValue> followFieldValues = followIds.stream()
                .filter(id -> !blocked.contains(id))
                .map(FieldValue::of)
                .toList();
        if (followFieldValues.isEmpty()) {
            return List.of();
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        int needed = (int) Math.min((long) safePage * safeSize, ES_RESULT_WINDOW);
        List<PreferencePort.TagPreferenceSummary> preferences = preferencePort.topPreferences(userId, 20);
        Set<String> dislikedIds = dislikedContentIds(userId);

        NativeQuery query;

        if (preferences.isEmpty()) {
            // 无偏好 → 只限定关注作者，按发布时间倒序
            query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        b.must(m -> m.term(t -> t.field("status").value("published")));
                        // 只查关注的人的内容
                        b.must(m -> m.terms(t -> t.field("authorId")
                                .terms(tv -> tv.value(followFieldValues))
                        ));
                        if (!dislikedIds.isEmpty()) {
                            b.mustNot(m -> m.ids(ids -> ids.values(dislikedIds.stream().toList())));
                        }
                        b.must(m -> m.term(t -> t.field("visibility").value("public")));
                        return b;
                    }))
                    .withSort(RECENT_ORDER)
                    .build();
        } else {
            // 有偏好 → 限定关注作者 + 标签偏好打分
            List<FunctionScore> functions = preferences.stream()
                    .map(p -> FunctionScore.of(f -> f.filter(fq -> fq.term(t -> t.field("tagIds").value(p.getTagId())))
                            .weight(p.getScore() > 0 ? (double) p.getScore() : 0)
                    ))
                    .toList();

            query = NativeQuery.builder()
                    .withQuery(q -> q.functionScore(fs -> fs.query(inner -> inner.bool(b -> {
                                b.must(m -> m.term(t -> t.field("status").value("published")));
                                // 只查关注的人的内容
                                b.must(m -> m.terms(t -> t.field("authorId")
                                        .terms(tv -> tv.value(followFieldValues))
                                ));
                                if (!dislikedIds.isEmpty()) {
                                    b.mustNot(m -> m.ids(ids -> ids.values(dislikedIds.stream().toList())));
                                }
                                b.must(m -> m.term(t -> t.field("visibility").value("public")));
                                return b;
                            }))
                            .functions(functions)
                            .scoreMode(FunctionScoreMode.Sum)
                            .boostMode(FunctionBoostMode.Replace)
                    ))
                    .withSort(SCORE_ORDER)
                    .build();
        }

        return page(recallUnviewed(query, userId, needed), safePage, safeSize);
    }
}

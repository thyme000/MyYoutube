package cn.diaojiahong.myyoutube.recommend.service.impl;


import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDoc;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import cn.diaojiahong.myyoutube.recommend.service.IRecommendService;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import co.elastic.clients.elasticsearch._types.FieldValue;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RecommendServiceImpl implements IRecommendService {

    private final IUserTagPreferenceService userTagPreferenceService;
    private final ElasticsearchOperations elasticsearchOperations;
    private final StringRedisTemplate redisTemplate;
    private final IUserFollowService userFollowService;

    /**
     * 推荐视频
     * @param userId 用户ID
     * @param category 分类
     * @param videoType 视频类型
     * @param page 页码
     * @param size 每页数量
     * @return 推荐视频ID列表
     */
    @Override
    public List<Long> recommend(Long userId, String category,
                                String videoType, int page, int size) {

        // 获取用户偏好信息 (对分数取降序(top20))
        List<UserTagPreference> preferences = userTagPreferenceService
                .lambdaQuery()
                .eq(UserTagPreference::getUserId, userId)
                .orderByDesc(UserTagPreference::getScore)
                .last("LIMIT 20")
                .list();

        // 构造ES查询
        NativeQuery query;
        Pageable pageable = PageRequest.of(page - 1, size);

        // 查询用户看过和踩过的视频id
        Set<String> viewedVideoIds = redisTemplate.opsForSet().members(RedisConstants.USER_VIEWED + userId);
        Set<String> dislikeVideoIds = redisTemplate.opsForSet().members(RedisConstants.USER_DISLIKE + userId);
        // 合并
        Set<String> excludeVideoIds = new HashSet<>();
        if (viewedVideoIds != null) {
            excludeVideoIds.addAll(viewedVideoIds);
        }
        if (dislikeVideoIds != null) {
            excludeVideoIds.addAll(dislikeVideoIds);
        }

        // 查询用户的关注列表
        List<Long> followIds = userFollowService.getFollowIds(userId);
        List<FunctionScore> followFunctions = followIds.stream().map(fid -> FunctionScore.of(f -> f
                .filter(fq -> fq.term(t -> t.field("authorId").value(fid)))
                // 提高被关注作者的视频权重
                .weight(50.0)
        )).toList();

        List<FunctionScore> allFunctions = new java.util.ArrayList<>(followFunctions);
        // 用 field_value_factor 把 playCount 转成分数
        allFunctions.add(FunctionScore.of(f -> f
                .fieldValueFactor(fvf -> fvf
                        .field("playCount")
                        .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                        .missing(0.0)  // playCount 不存在时当 0
                )
        ));

        // 判断用户是否有偏好(新用户不具有偏好) -> 按热度推荐
        if (preferences.isEmpty()) {

            // 不具有
            query = NativeQuery.builder()
                    .withQuery(q -> q.functionScore(fs -> fs
                            .query(inner -> inner.bool(b -> {
                                    b.must(m -> m.term(t -> t.field("status").value("published")));
                                    if (category != null) {
                                    b.must(m -> m.term(t -> t.field("category").value(category)));
                                    }
                                    if (videoType != null) {
                                    b.must(m -> m.term(t -> t.field("videoType").value(videoType)));
                                    }
                                    if (!excludeVideoIds.isEmpty()) {
                                    b.mustNot(m -> m.ids(ids -> ids.values(excludeVideoIds.stream().toList())));
                                    }
                                    b.mustNot(m -> m.term(t -> t.field("authorId").value(userId)));
                                    b.must(m -> m.term(t -> t.field("visibility").value("public")));
                                    return b;
                                }
                            )) // 按播放量和被关注用户的视频综合排列
                            .functions(allFunctions)
                            .scoreMode(FunctionScoreMode.Sum)
                            .boostMode(FunctionBoostMode.Replace)

                    ))
                    .withPageable(pageable)
                    .build();
        } else {
            // 按用户喜好推荐
            List<FunctionScore> functions = preferences.stream()
                    .map(p -> FunctionScore.of(f -> f
                            .filter(fq -> fq.term(t -> t.field("tagIds").value(p.getTagId())))
                            .weight(p.getScore() > 0 ? (double) p.getScore() : 0) // es里不允许权重为负数
                    ))
                    .toList();
            allFunctions.addAll(functions);

            query = NativeQuery.builder()
                    .withQuery(q -> q.functionScore(fs -> fs
                            .query(inner -> inner.bool(b -> {
                                        b.must(m -> m.term(t -> t.field("status").value("published")));
                                        if (category != null) {
                                            b.must(m -> m.term(t -> t.field("category").value(category)));
                                        }
                                        if (videoType != null) {
                                            b.must(m -> m.term(t -> t.field("videoType").value(videoType)));
                                        }
                                        if (!excludeVideoIds.isEmpty()) {
                                            b.mustNot(m -> m.ids(ids -> ids.values(excludeVideoIds.stream().toList())));
                                        }
                                        b.mustNot(m -> m.term(t -> t.field("authorId").value(userId)));
                                        b.must(m -> m.term(t -> t.field("visibility").value("public")));
                                        return b;
                                    }
                            ))
                            .functions(allFunctions)
                            .scoreMode(FunctionScoreMode.Sum)
                            .boostMode(FunctionBoostMode.Replace)
                    ))
                    .withPageable(pageable)
                    .build();
        }

        // 3. 执行查询
        SearchHits<VideoDoc> hits = elasticsearchOperations.search(query, VideoDoc.class);

        return hits.getSearchHits().stream()
                .map(h -> h.getContent().getId())
                .toList();

    }

    /**
     * 从被关注用户中推荐视频
     * @param followIds 被关注用户的id列表
     * @return
     */
    @Override
    public List<Long> recommendForFollow(List<Long> followIds, Long userId, int page, int size) {
        // 没有关注的人，直接返回空
        if (followIds == null || followIds.isEmpty()) {
            return List.of();
        }

        List<UserTagPreference> preferences = userTagPreferenceService
                .lambdaQuery()
                .eq(UserTagPreference::getUserId, userId)
                .orderByDesc(UserTagPreference::getScore)
                .last("LIMIT 20")
                .list();

        Pageable pageable = PageRequest.of(page - 1, size);

        Set<String> viewedVideoIds = redisTemplate.opsForSet().members(RedisConstants.USER_VIEWED + userId);
        Set<String> dislikeVideoIds = redisTemplate.opsForSet().members(RedisConstants.USER_DISLIKE + userId);
        Set<String> excludeVideoIds = new HashSet<>();
        if (viewedVideoIds != null) {
            excludeVideoIds.addAll(viewedVideoIds);
        }
        if (dislikeVideoIds != null) {
            excludeVideoIds.addAll(dislikeVideoIds);
        }

        // 把 followIds 转为 ES terms 需要的 FieldValue 列表
        List<co.elastic.clients.elasticsearch._types.FieldValue> followFieldValues = followIds.stream()
                .map(FieldValue::of)
                .toList();

        NativeQuery query;

        if (preferences.isEmpty()) {
            // 无偏好 → 只限定关注作者，按发布时间倒序
            query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        b.must(m -> m.term(t -> t.field("status").value("published")));
                        // 只查关注的人的视频
                        b.must(m -> m.terms(t -> t
                                .field("authorId")
                                .terms(tv -> tv.value(followFieldValues))
                        ));
                        if (!excludeVideoIds.isEmpty()) {
                            b.mustNot(m -> m.ids(ids -> ids.values(excludeVideoIds.stream().toList())));
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
                                // 只查关注的人的视频
                                b.must(m -> m.terms(t -> t
                                        .field("authorId")
                                        .terms(tv -> tv.value(followFieldValues))
                                ));
                                if (!excludeVideoIds.isEmpty()) {
                                    b.mustNot(m -> m.ids(ids -> ids.values(excludeVideoIds.stream().toList())));
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

        SearchHits<VideoDoc> hits = elasticsearchOperations.search(query, VideoDoc.class);
        return hits.getSearchHits().stream()
                .map(h -> h.getContent().getId())
                .toList();
    }
}

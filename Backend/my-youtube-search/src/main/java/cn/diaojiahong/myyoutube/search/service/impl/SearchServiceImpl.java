package cn.diaojiahong.myyoutube.search.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDoc;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchUserPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoByTagPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoPageQuery;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchUserVO;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchVideoVO;
import cn.diaojiahong.myyoutube.search.service.ISearchService;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.UserVideoPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.hutool.core.bean.BeanUtil;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements ISearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final FollowPort followPort;
    private final UserVideoPort userVideoPort;
    private final ContentSummaryPort contentSummaryPort;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final StringRedisTemplate redisTemplate;
    private final ElasticsearchClient elasticsearchClient;
    private final ContentVectorService contentVectorService;
    private final VectorProperties vectorProperties;
    private static final int STORAGE_EXPIRE_SECONDS = 3600 * 10;

    @Override
    public PageResult<SearchVideoVO> searchVideo(SearchVideoPageQuery pageQuery) {
        Long userId = getUserId();
        // 用户搜索的关键词
        String keyword = pageQuery.getKeyword() == null ? "" : pageQuery.getKeyword().trim();
        Pageable pageable = PageRequest.of(pageQuery.getPageNo() - 1, pageQuery.getPageSize());

        // 最新排序分支不打分, 关注列表只在综合排序分支查一次
        List<Long> followIds = List.of();
        // 查询向量一次生成两分支共用; 开关关闭/无关键词/llm-svc 不可用都返回 null, 整链路降级纯关键词
        boolean wantVector = vectorProperties.isEnabled() && !keyword.isEmpty();
        ContentVectorService.QueryVector queryVector = wantVector ? contentVectorService.embedQuery(keyword) : null;

        if (!pageQuery.isSortByLatest()) {
            FunctionBoost boost = getFunction(userId);
            followIds = boost.followIds();

            if (queryVector == null) {
                // 纯关键词单路
                NativeQuery query = comprehensiveQuery(pageQuery, keyword, pageable, boost);
                PageResult<SearchVideoVO> result = getSearchPageResult(query, pageQuery.getPageNo(), pageQuery.getPageSize(), followIds);
                loadForHotSearch(userId, keyword);
                return result;
            }
            // 双路召回 RRF 融合: 关键词路取满召回深度, 分页窗口在融合结果上切
            NativeQuery keywordQuery = comprehensiveQuery(pageQuery, keyword,
                    PageRequest.of(0, vectorProperties.getRecallSize()), boost);
            SearchHits<ContentDoc> kwHits = elasticsearchOperations.search(keywordQuery, ContentDoc.class);
            List<String> keywordIds = kwHits.getSearchHits().stream()
                    .map(hit -> hit.getContent().getId().toString()).toList();
            List<String> vectorIds = contentVectorService.knnSearch(queryVector, pageQuery.getCategory(),
                    vectorProperties.getRecallSize());
            List<String> fused = contentVectorService.rrfFuse(keywordIds, vectorIds);
            // total 用融合结果真实长度: 语义模式的召回以 recall-size 封顶, 深分页不可达就不虚报
            PageResult<SearchVideoVO> result = buildPageResult(fused, fused.size(),
                    pageQuery.getPageNo(), pageQuery.getPageSize(), followIds);
            loadForHotSearch(userId, keyword);
            return result;
        } else {
            // 按能匹配关键字的基础上，按最新时间排视频; 向量只当召回过滤(kNN 命中并入结果集, 排序仍按 createTime)
            NativeQuery query = latestQuery(pageQuery, keyword, userId, pageable, queryVector);
            PageResult<SearchVideoVO> result = getSearchPageResult(query, pageQuery.getPageNo(), pageQuery.getPageSize(), followIds);
            loadForHotSearch(userId, keyword);
            return result;
        }
    }

    private NativeQuery comprehensiveQuery(SearchVideoPageQuery pageQuery, String keyword,
                                           Pageable pageable, FunctionBoost boost) {
        return NativeQuery.builder()
                .withQuery(q -> q.functionScore(fs -> fs
                        .query(inner -> inner.bool(b -> {
                                    b.must(m -> m.term(t -> t.field("status").value("published")));
                                    b.must(m -> m.term(t -> t.field("visibility").value("public")));
                                    if (pageQuery.getCategory() != null) {
                                        b.must(m -> m.term(t -> t.field("category").value(pageQuery.getCategory())));
                                    }
                                    if (!keyword.isEmpty()) {
                                        b.must(m -> m.multiMatch(mm -> mm
                                                .query(keyword)
                                                .fields("title^5", "tagNames^4", "authorName^2", "description^3")
                                                .fuzziness("AUTO")
                                                .minimumShouldMatch("40%")
                                        ));
                                    }
                                    return b;
                                }
                        )) // 按播放量,获赞数和被关注用户的视频综合排列
                        .functions(boost.functions())
                        .scoreMode(FunctionScoreMode.Sum)
                        .boostMode(FunctionBoostMode.Sum)
                ))
                .withSort(Sort.by(Sort.Order.desc("_score"), Sort.Order.desc("createTime")))
                .withPageable(pageable)
                .build();
    }

    private NativeQuery latestQuery(SearchVideoPageQuery pageQuery, String keyword, Long userId,
                                    Pageable pageable, ContentVectorService.QueryVector queryVector) {
        var builder = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    b.must(m -> m.term(t -> t.field("status").value("published")));
                    b.mustNot(m -> m.term(t -> t.field("authorId").value(userId)));
                    b.must(m -> m.term(t -> t.field("visibility").value("public")));
                    if (pageQuery.getCategory() != null) {
                        b.must(m -> m.term(t -> t.field("category").value(pageQuery.getCategory())));
                    }
                    if (!keyword.isEmpty()) {
                        b.must(m -> m.multiMatch(mm -> mm
                                .query(keyword)
                                .fields("title^5", "tagNames^4", "authorName^2", "description^3")
                                .fuzziness("AUTO")
                                .minimumShouldMatch("40%")
                        ));
                    }
                    return b;
                }))
                .withSort(Sort.by(Sort.Order.desc("createTime"), Sort.Order.desc("id")))
                .withPageable(pageable);
        if (queryVector != null) {
            builder.withKnnSearches(knnRecall(queryVector, pageQuery.getCategory()));
        }
        return builder.build();
    }

    /** kNN 召回子句: 只召回与查询向量同版本、已发布的公开内容 */
    private KnnSearch knnRecall(ContentVectorService.QueryVector queryVector, String category) {
        int size = vectorProperties.getRecallSize();
        return KnnSearch.of(k -> k
                .field("vector")
                .queryVector(queryVector.vector())
                .k(size)
                .numCandidates(Math.max(size * 3, 100))
                .filter(f -> f.bool(b -> {
                    b.must(m -> m.term(t -> t.field("status").value("published")));
                    b.must(m -> m.term(t -> t.field("visibility").value("public")));
                    if (category != null) {
                        b.must(m -> m.term(t -> t.field("category").value(category)));
                    }
                    b.must(m -> m.term(t -> t.field("vectorVersion").value(queryVector.version())));
                    return b;
                }))
        );
    }

    @Override
    public PageResult<SearchUserVO> searchUser(SearchUserPageQuery pageQuery) {
        Long userId = getUserId();
        // 获取关键字
        String keyword = pageQuery.getKeyword() == null ? "" : pageQuery.getKeyword().trim();

        // 查询用户的关注列表
        List<Long> followIds = followPort.getFollowIds(userId);
        List<FunctionScore> followFunctions = followIds.stream().map(fid -> FunctionScore.of(f -> f
                .filter(fq -> fq.term(t -> t.field("id").value(fid)))
                .weight(5.0)
        )).toList();
        List<FunctionScore> allFunctions = new java.util.ArrayList<>(followFunctions);
        // 用 field_value_factor 把 fans 转成分数
        allFunctions.add(FunctionScore.of(f -> f
                .fieldValueFactor(fvf -> fvf
                        .field("fans")
                        .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                        .missing(0.0)  // fans 不存在时当 0
                )
        ));
        // 用 field_value_factor 把 likeCount 转成分数
        allFunctions.add(FunctionScore.of(f -> f
                .fieldValueFactor(fvf -> fvf
                        .field("likeCount")
                        .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                        .missing(0.0)  // likeCount 不存在时当 0
                )
        ));

        // 构造ES查询
        NativeQuery query;
        Pageable pageable = PageRequest.of(pageQuery.getPageNo() - 1, pageQuery.getPageSize());
        // 检查关键字是否为数字
        boolean numeric = keyword.matches("\\d+");

        query = NativeQuery.builder()
                .withQuery(q -> q.functionScore(fs -> fs
                        .query(inner -> inner.bool(b -> {
                                    if (!keyword.isEmpty()) {
                                        b.should(m -> m.multiMatch(mm -> mm
                                                .query(keyword)
                                                .fields("nickname^5", "introduction^2")
                                                .fuzziness("AUTO")
                                                .minimumShouldMatch("40%")
                                        ));
                                        if (numeric) {
                                            b.should(s -> s.wildcard(t -> t.field("idKeyword").value("*" + keyword + "*")));
                                        }
                                        b.minimumShouldMatch("1");
                                    }
                                    return b;
                                }
                        )) // 按播放量,获赞数和被关注用户的视频综合排列
                        .functions(allFunctions)
                        .scoreMode(FunctionScoreMode.Sum)
                        .boostMode(FunctionBoostMode.Sum)
                ))
                .withSort(Sort.by(Sort.Order.desc("_score"), Sort.Order.desc("createTime")))
                .withPageable(pageable)
                .build();
        // 查询
        SearchHits<UserDoc> hits = elasticsearchOperations.search(query, UserDoc.class);
        List<UserDoc> list = hits.getSearchHits().stream().map(SearchHit::getContent).toList();
        if (list.isEmpty()) {
            return PageResult.of(0, 0, Collections.emptyList());
        }
        // 转换为VO
        List<SearchUserVO> result = BeanUtil.copyToList(list, SearchUserVO.class);
        // 填充头像url
        Map<Long, String> avatarUrls = UserSummaries.toAvatarMap(
                userPort.listByIds(result.stream().map(SearchUserVO::getId).collect(Collectors.toList())));
        if (avatarUrls == null || avatarUrls.isEmpty()) {
            return PageResult.of(0, 0, result);
        }
        result.forEach(vo -> {
            vo.setAvatarUrl(storageUtil.signGetUrl(avatarUrls.get(vo.getId()), STORAGE_EXPIRE_SECONDS));
        });

        loadForHotSearch(userId, keyword);
        // 判断是否关注过
        Map<Long, Boolean> isFollows = followIds.stream().collect(Collectors.toMap(id -> id, id -> true));
        result.forEach(vo -> {
            vo.setIsFollow(isFollows.getOrDefault(vo.getId(), false));
        });

        long total = hits.getTotalHits();
        long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();
        return PageResult.of(total, pages, result);
    }

    private void loadForHotSearch(Long userId, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return;
        }
        String userSearchKeywordKey = RedisConstants.USER_SEARCH_KEYWORD + userId;
        // 标准化关键词
        String dedupKeyword = StrUtils.normalizeDedupKeyword(keyword);
        String displayKeyword = StrUtils.normalizeDisplayKeyword(keyword);
        Boolean search = redisTemplate.opsForSet().isMember(userSearchKeywordKey, dedupKeyword);
        if (search == null || !search) {
            // 搜索成功, 给这个关键词加分
            String hotSearchKey = RedisConstants.HOT_SEARCH;
            redisTemplate.opsForZSet().incrementScore(hotSearchKey, displayKeyword, 1);
            redisTemplate.expire(hotSearchKey, RedisConstants.HOT_SEARCH_EXPIRE, TimeUnit.DAYS);
            // 记录用户搜索过的关键词
            redisTemplate.opsForSet().add(userSearchKeywordKey, dedupKeyword);
            redisTemplate.expire(userSearchKeywordKey, RedisConstants.USER_SEARCH_KEYWORD_EXPIRE, TimeUnit.MINUTES);
        }
    }

    @Override
    public List<String> prompt(String keyword) {
        String q = keyword == null ? "" : keyword.trim();
        if (q.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            var videoResp = elasticsearchClient.search(s -> s
                    .index("content")
                    .suggest(su -> su.suggesters("video_suggest", sg -> sg
                            .prefix(q)
                            .completion(c -> c.field("suggest").size(8).skipDuplicates(true))
                    )), Void.class);

            var userResp = elasticsearchClient.search(s -> s
                    .index("user")
                    .suggest(su -> su.suggesters("user_suggest", sg -> sg
                            .prefix(q)
                            .completion(c -> c.field("suggest").size(6).skipDuplicates(true))
                    )), Void.class);

            Set<String> merged = new LinkedHashSet<>();
            appendSuggestTexts(videoResp, "video_suggest", merged);
            appendSuggestTexts(userResp, "user_suggest", merged);

            return merged.stream().limit(10).toList();
        } catch (Exception e) {
            log.error("提示查询失败,请稍后重试", e);
            throw new BizException(BizException.BIZ_ERROR, "系统繁忙,请稍后再试");
        }
    }

    private void appendSuggestTexts(co.elastic.clients.elasticsearch.core.SearchResponse<Void> response,
                                    String suggestName,
                                    Set<String> merged) {
        if (response == null || response.suggest() == null) {
            return;
        }
        var entries = response.suggest().get(suggestName);
        if (entries == null || entries.isEmpty()) {
            return;
        }
        var completion = entries.get(0).completion();
        if (completion == null || completion.options() == null) {
            return;
        }
        completion.options().forEach(opt -> {
            String text = opt.text();
            if (text != null && !text.isBlank()) {
                merged.add(text.trim());
            }
        });
    }

    @Override
    public PageResult<SearchVideoVO> searchByTag(SearchVideoByTagPageQuery pageQuery) {
        Long userId = getUserId();
        String tagName = pageQuery.getTagName() == null ? "" : pageQuery.getTagName().trim();
        // 构造ES查询
        NativeQuery query;
        Pageable pageable = PageRequest.of(pageQuery.getPageNo() - 1, pageQuery.getPageSize());
        // 查询用户的关注列表
        FunctionBoost boost = getFunction(userId);

        query = NativeQuery.builder()
                .withQuery(q -> q.functionScore(fs -> fs
                        .query(inner -> inner.bool(b -> {
                                    b.must(m -> m.term(t -> t.field("status").value("published")));
                                    b.must(m -> m.term(t -> t.field("visibility").value("public")));
                                    if (!tagName.isEmpty()) {
                                        b.must(m -> m.multiMatch(mm -> mm
                                                .query(tagName)
                                                .fields("title^2", "tagNames^6", "description^1")
                                                .fuzziness("AUTO")
                                                .minimumShouldMatch("60%")
                                        ));
                                    }
                                    return b;
                                }
                        )) // 按播放量,获赞数和被关注用户的视频综合排列
                        .functions(boost.functions())
                        .scoreMode(FunctionScoreMode.Sum)
                        .boostMode(FunctionBoostMode.Sum)
                ))
                .withSort(Sort.by(Sort.Order.desc("_score"), Sort.Order.desc("createTime")))
                .withPageable(pageable)
                .build();

        // 3. 执行查询
        PageResult<SearchVideoVO> result = getSearchPageResult(query, pageQuery.getPageNo(), pageQuery.getPageSize(), boost.followIds());
        loadForHotSearch(userId, tagName);
        return result;
    }

    @Override
    public List<String> hot() {
        // 倒序获取热门搜索的前七个
        Set<String> hotSearches = redisTemplate.opsForZSet()
                .reverseRangeByScore(
                        RedisConstants.HOT_SEARCH,  // 键名
                        Double.NEGATIVE_INFINITY,    // 最小分数（负无穷）
                        Double.POSITIVE_INFINITY,    // 最大分数（正无穷）
                        0,                           // 偏移量（从第0个开始）
                        7                            // 数量（取前7个）
                );
        if (hotSearches == null || hotSearches.isEmpty()) {
            return Collections.emptyList();
        }
        return hotSearches.stream().toList();
    }

    private PageResult<SearchVideoVO> getSearchPageResult(NativeQuery query, int pageNo, int pageSize, List<Long> followIds) {
        SearchHits<ContentDoc> hits = elasticsearchOperations.search(query, ContentDoc.class);
        List<String> orderedIds = hits.getSearchHits().stream()
                .map(hit -> hit.getContent().getId().toString()).toList();
        return buildPageResult(orderedIds, hits.getTotalHits(), pageNo, pageSize, followIds);
    }

    /**
     * 按 id 顺序回填详情: 传入顺序(ES 相关性序或 RRF 融合序)经 LinkedHashSet 透传,
     * 前端看到的顺序与召回排序一致
     */
    private PageResult<SearchVideoVO> buildPageResult(List<String> orderedIds, long total,
                                                      int pageNo, int pageSize, List<Long> followIds) {
        long pages = (total + pageSize - 1) / pageSize;
        int from = (pageNo - 1) * pageSize;
        if (from >= orderedIds.size()) {
            return PageResult.of(total, pages, Collections.emptyList());
        }
        List<String> pageIds = orderedIds.subList(from, Math.min(from + pageSize, orderedIds.size()));
        List<ContentSummary> videos = contentSummaryPort.listByIdsOrdered(new LinkedHashSet<>(pageIds));
        List<SearchVideoVO> result = BeanUtil.copyToList(videos, SearchVideoVO.class);
        // 填充用户昵称和头像url(一次批量取摘要, 本地投影)
        List<UserSummary> authors = userPort.listByIds(videos.stream().map(ContentSummary::getAuthorId).collect(Collectors.toList()));
        Map<Long, String> authorNicknames = UserSummaries.toNicknameMap(authors);
        Map<Long, String> authorAvatarUrls = UserSummaries.toAvatarMap(authors);
        if (authorNicknames == null || authorAvatarUrls == null) {
            return PageResult.of(0, 0, result);
        }
        result.forEach(vo -> {
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), STORAGE_EXPIRE_SECONDS));
        });
        // 视频url,封面url
        result.forEach(re -> {
            re.setVideoUrl(storageUtil.signGetUrl(re.getVideoUrl(), STORAGE_EXPIRE_SECONDS));
            re.setCoverUrl(storageUtil.signGetUrl(re.getCoverUrl(), STORAGE_EXPIRE_SECONDS));
        });
        // 判断是否点过赞
        List<Long> likedIds = isLike(getUserId(), videos.stream().map(ContentSummary::getId).collect(Collectors.toList()));
        if (likedIds != null && !likedIds.isEmpty()) {
            Map<Long, Boolean> isLikes = likedIds.stream().collect(Collectors.toMap(id -> id, id -> true));
            result.forEach(vo -> {
                vo.setIsLike(isLikes.getOrDefault(Long.valueOf(vo.getId()), false));
            });
        }
        // 填充是否关注
        if (followIds != null && !followIds.isEmpty()) {
            Map<Long, Boolean> isFollows = followIds.stream().collect(Collectors.toMap(id -> id, id -> true));
            result.forEach(vo -> {
                vo.setIsFollow(isFollows.getOrDefault(vo.getAuthorId(), false));
            });
        }
        return PageResult.of(total, pages, result);
    }

    private List<Long> isLike(Long userId, List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Boolean> likedMap = userVideoPort.likedMap(userId, new HashSet<>(videoIds.stream().map(String::valueOf).toList()));
        return likedMap.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
    }

    /** 一次 getFollowIds 同时喂 ES 打分函数与 isFollow 回填, 避免同一请求查两次关注列表 */
    private record FunctionBoost(List<FunctionScore> functions, List<Long> followIds) {
    }

    private FunctionBoost getFunction(Long userId) {
        // 查询用户的关注列表
        List<Long> followIds = followPort.getFollowIds(userId);
        List<FunctionScore> followFunctions = followIds.stream().map(fid -> FunctionScore.of(f -> f
                .filter(fq -> fq.term(t -> t.field("authorId").value(fid)))
                // 提高被关注作者的视频权重
                .weight(5.0)
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
        // 用 field_value_factor 把 likeCount 转成分数
        allFunctions.add(FunctionScore.of(f -> f
                .fieldValueFactor(fvf -> fvf
                        .field("likeCount")
                        .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                        .missing(0.0)  // likeCount 不存在时当 0
                )
        ));
        return new FunctionBoost(allFunctions, followIds);
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.parseLong(userId);
    }
}

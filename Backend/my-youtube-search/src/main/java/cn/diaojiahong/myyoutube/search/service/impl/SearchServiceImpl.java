package cn.diaojiahong.myyoutube.search.service.impl;

import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
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
import cn.diaojiahong.myyoutube.search.hot.HotSearchService;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.ContentInternalPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.hutool.core.bean.BeanUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import cn.diaojiahong.myyoutube.search.recall.RecallPipeline;
import cn.diaojiahong.myyoutube.search.recall.SearchContext;
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
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements ISearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final FollowPort followPort;
    private final ContentInternalPort contentInternalPort;
    private final ContentSummaryPort contentSummaryPort;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final HotSearchService hotSearchService;
    private final ElasticsearchClient elasticsearchClient;
    private final RecallPipeline recallPipeline;
    private static final int STORAGE_EXPIRE_SECONDS = 3600 * 10;

    @Override
    @ObservedBusiness(BusinessOperation.SEARCH_CONTENT)
    public PageResult<SearchVideoVO> searchVideo(SearchVideoPageQuery pageQuery) {
        Long userId = getUserId();
        // 关注状态是响应必需数据，最新排序与任一单路返回也必须取得；失败交由接口报错。
        SearchContext ctx = SearchContext.of(pageQuery, userId, followPort.getFollowIds(userId));
        List<String> fused = recallPipeline.execute(ctx);
        // total 用融合结果真实长度: 召回以 recall-size 封顶, 深分页不可达就不虚报
        var readable = contentSummaryPort.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(fused, null));
        int from = Math.min((pageQuery.getPageNo() - 1) * pageQuery.getPageSize(), readable.size());
        var page = readable.subList(from, Math.min(from + pageQuery.getPageSize(), readable.size()));
        PageResult<SearchVideoVO> result = buildPageResult(page, readable.size(), pageQuery.getPageSize(), ctx.getFollowIds());
        if (pageQuery.getPageNo() == 1 && !result.getList().isEmpty()) {
            hotSearchService.record(userId, ctx.getKeyword());
        }
        // 逐次检索, 只留召回量/可读量便于核对降级是否发生
        log.debug("Search completed userId={} keyword={} sortByLatest={} recallHits={} readable={} pageNo={}",
                userId, ctx.getKeyword(), pageQuery.isSortByLatest(), fused.size(), readable.size(), pageQuery.getPageNo());
        return result;
    }

    @Override
    @ObservedBusiness(BusinessOperation.SEARCH_USER)
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

        // 判断是否关注过
        Map<Long, Boolean> isFollows = followIds.stream().collect(Collectors.toMap(id -> id, id -> true));
        result.forEach(vo -> {
            vo.setIsFollow(isFollows.getOrDefault(vo.getId(), false));
        });

        long total = hits.getTotalHits();
        long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();
        return PageResult.of(total, pages, result);
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
            var entries = videoResp.suggest().get("video_suggest");
            var ids = entries == null ? List.<String>of() : entries.stream()
                    .flatMap(entry -> entry.completion().options().stream())
                    .map(option -> option.id()).filter(Objects::nonNull).distinct().toList();
            var readable = contentSummaryPort.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(ids, null))
                    .stream().map(summary -> summary.getId().toString()).collect(Collectors.toSet());
            appendSuggestTexts(videoResp, "video_suggest", merged, readable::contains);
            appendSuggestTexts(userResp, "user_suggest", merged, id -> true);

            return merged.stream().limit(10).toList();
        } catch (Exception e) {
            log.error("搜索提示查询失败 keyword={} errorType={}", q, e.getClass().getSimpleName(), e);
            throw new BizException(BizException.BIZ_ERROR, "系统繁忙,请稍后再试");
        }
    }

    private void appendSuggestTexts(co.elastic.clients.elasticsearch.core.SearchResponse<Void> response,
                                    String suggestName,
                                    Set<String> merged, java.util.function.Predicate<String> readable) {
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
            if (readable.test(opt.id()) && text != null && !text.isBlank()) {
                merged.add(text.trim());
            }
        });
    }

    @Override
    @ObservedBusiness(BusinessOperation.SEARCH_TAG)
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
        return result;
    }

    @Override
    public List<String> hot() {
        return hotSearchService.hot();
    }

    private PageResult<SearchVideoVO> getSearchPageResult(NativeQuery query, int pageNo, int pageSize, List<Long> followIds) {
        SearchHits<ContentDoc> hits = elasticsearchOperations.search(query, ContentDoc.class);
        List<String> orderedIds = hits.getSearchHits().stream()
                .map(hit -> hit.getContent().getId().toString()).toList();
        return buildPageResult(contentSummaryPort.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(orderedIds, null)),
                hits.getTotalHits(), pageSize, followIds);
    }

    private PageResult<SearchVideoVO> buildPageResult(List<ContentSummary> videos, long total,
                                                      int pageSize, List<Long> followIds) {
        long pages = (total + pageSize - 1) / pageSize;
        if (videos.isEmpty()) return PageResult.of(total, pages, List.of());
        List<SearchVideoVO> result = BeanUtil.copyToList(videos, SearchVideoVO.class);
        Set<Long> followedAuthors = new HashSet<>(followIds);
        result.forEach(vo -> vo.setIsFollow(followedAuthors.contains(vo.getAuthorId())));
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
        return PageResult.of(total, pages, result);
    }

    private List<Long> isLike(Long userId, List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, Boolean> likedMap = contentInternalPort.likedMap(userId, new HashSet<>(videoIds));
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

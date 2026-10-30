package cn.diaojiahong.myyoutube.search.service.impl;

import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import cn.diaojiahong.myyoutube.search.domain.doc.UserDoc;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchUserPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
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
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import cn.diaojiahong.myyoutube.search.recall.ContentSearchPage;
import cn.diaojiahong.myyoutube.search.recall.SearchContext;
import cn.diaojiahong.myyoutube.search.recall.SearchContentSummaryClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.CompletionSuggest;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
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
    private final SearchContentSummaryClient contentSummaryPort;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final HotSearchService hotSearchService;
    private final ElasticsearchClient elasticsearchClient;
    private final ContentSearchPage contentSearchPage;
    private static final int STORAGE_EXPIRE_SECONDS = 3600 * 10;

    @Override
    @ObservedBusiness(BusinessOperation.SEARCH_CONTENT)
    public CursorPage<SearchVideoVO, String> searchVideo(ContentSearchCursorQuery query) {
        query.validate();
        Long userId = getUserId();
        SearchContext context = SearchContext.of(query, userId, followPort.getFollowIds(userId));
        CursorPage<ContentSummary, String> page = contentSearchPage.search(context);
        List<SearchVideoVO> items = buildCards(page.items(), context.getFollowIds());

        boolean first = query.getCursor() == null || query.getCursor().isBlank();
        if (first && !items.isEmpty()) hotSearchService.record(userId, query.getKeyword());
        log.debug("Search completed userId={} keyword={} latest={} count={} first={}",
                userId, context.getKeyword(), query.isSortByLatest(), items.size(), first);
        return new CursorPage<>(items, page.nextCursor());
    }

    @Override
    @ObservedBusiness(BusinessOperation.SEARCH_USER)
    public PageResult<SearchUserVO> searchUser(SearchUserPageQuery pageQuery) {
        Long userId = getUserId();
        // 获取关键字
        String keyword = pageQuery.getKeyword() == null ? "" : pageQuery.getKeyword().trim();

        // 查询用户的关注列表
        List<Long> followIds = followPort.getFollowIds(userId);
        List<FunctionScore> followFunctions = followIds.stream()
                .map(fid -> FunctionScore.of(f -> f.filter(fq -> fq.term(t -> t.field("id").value(fid)))
                .weight(5.0)
        )).toList();
        List<FunctionScore> allFunctions = new java.util.ArrayList<>(followFunctions);
        // 用 field_value_factor 把 fans 转成分数
        allFunctions.add(FunctionScore.of(f -> f.fieldValueFactor(fvf -> fvf.field("fans")
                        .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                        .missing(0.0)
                        // fans 不存在时当 0
                )
        ));
        // 用 field_value_factor 把 likeCount 转成分数
        allFunctions.add(FunctionScore.of(f -> f.fieldValueFactor(fvf -> fvf.field("likeCount")
                        .modifier(co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier.Log1p)
                        .missing(0.0)
                        // likeCount 不存在时当 0
                )
        ));

        // 构造ES查询
        NativeQuery query;
        Pageable pageable = PageRequest.of(pageQuery.getPageNo() - 1, pageQuery.getPageSize());
        // 检查关键字是否为数字
        boolean numeric = keyword.matches("\\d+");

        query = NativeQuery.builder()
                .withQuery(q -> q.functionScore(fs -> fs.query(inner -> inner.bool(b -> {
                                    if (!keyword.isEmpty()) {
                                        b.should(m -> m.multiMatch(mm -> mm.query(keyword)
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
                        ))
                        // 按播放量,获赞数和被关注用户的视频综合排列
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
            SearchResponse<Void> videoResp = elasticsearchClient.search(s -> s.index("content")
                    .suggest(su -> su.suggesters("video_suggest", sg -> sg.prefix(q)
                            .completion(c -> c.field("suggest").size(8).skipDuplicates(true))
                    )), Void.class);

            SearchResponse<Void> userResp = elasticsearchClient.search(s -> s.index("user")
                    .suggest(su -> su.suggesters("user_suggest", sg -> sg.prefix(q)
                            .completion(c -> c.field("suggest").size(6).skipDuplicates(true))
                    )), Void.class);

            Set<String> merged = new LinkedHashSet<>();
            List<Suggestion<Void>> entries = videoResp.suggest().get("video_suggest");
            List<String> ids = entries == null ? List.<String>of() : entries.stream()
                    .flatMap(entry -> entry.completion().options().stream())
                    .map(option -> option.id()).filter(Objects::nonNull).distinct().toList();
            Set<String> readable = contentSummaryPort.listByIdsOrdered(new ContentSummaryPort.SummaryQuery(ids, null))
                    .stream().map(summary -> summary.getId().toString()).collect(Collectors.toSet());
            appendSuggestTexts(videoResp, "video_suggest", merged, readable::contains);
            appendSuggestTexts(userResp, "user_suggest", merged, id -> true);

            return merged.stream().limit(10).toList();
        } catch (Exception e) {
            throw new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "搜索提示暂时不可用", e);
        }
    }

    private void appendSuggestTexts(co.elastic.clients.elasticsearch.core.SearchResponse<Void> response,
                                    String suggestName,
                                    Set<String> merged, java.util.function.Predicate<String> readable) {
        if (response == null || response.suggest() == null) {
            return;
        }

        List<Suggestion<Void>> entries = response.suggest().get(suggestName);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        CompletionSuggest<Void> completion = entries.get(0).completion();
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
    public CursorPage<SearchVideoVO, String> searchByTag(TagSearchCursorQuery pageQuery) {
        Long userId = getUserId();
        pageQuery.validate();
        SearchContext ctx = SearchContext.forTag(pageQuery, userId, followPort.getFollowIds(userId));
        CursorPage<ContentSummary, String> page = contentSearchPage.search(ctx);
        return new CursorPage<>(buildCards(page.items(), ctx.getFollowIds()), page.nextCursor());
    }

    @Override
    public List<String> hot() {
        return hotSearchService.hot();
    }

    private List<SearchVideoVO> buildCards(List<ContentSummary> videos, List<Long> followIds) {
        if (videos.isEmpty()) return List.of();
        List<SearchVideoVO> result = BeanUtil.copyToList(videos, SearchVideoVO.class);
        Set<Long> followedAuthors = new HashSet<>(followIds);
        result.forEach(vo -> vo.setIsFollow(followedAuthors.contains(vo.getAuthorId())));
        // 填充用户昵称和头像url(一次批量取摘要, 本地投影)
        List<UserSummary> authors = userPort.listByIds(videos.stream().map(ContentSummary::getAuthorId).collect(Collectors.toList()));
        Map<Long, String> authorNicknames = UserSummaries.toNicknameMap(authors);
        Map<Long, String> authorAvatarUrls = UserSummaries.toAvatarMap(authors);
        if (authorNicknames == null || authorAvatarUrls == null) {
            return result;
        }

        result.forEach(vo -> {
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(
                    authorAvatarUrls.get(vo.getAuthorId()),
                    STORAGE_EXPIRE_SECONDS));
        });
        // 视频url,封面url
        result.forEach(re -> {
            re.setVideoUrl(storageUtil.signGetUrl(re.getVideoUrl(), STORAGE_EXPIRE_SECONDS));
            re.setCoverUrl(storageUtil.signGetUrl(re.getCoverUrl(), STORAGE_EXPIRE_SECONDS));
        });
        // 判断是否点过赞
        List<Long> likedIds = isLike(
                getUserId(),
                videos.stream().map(ContentSummary::getId).collect(Collectors.toList()));
        if (likedIds != null && !likedIds.isEmpty()) {
            Map<Long, Boolean> isLikes = likedIds.stream().collect(Collectors.toMap(id -> id, id -> true));
            result.forEach(vo -> {
                vo.setIsLike(isLikes.getOrDefault(Long.valueOf(vo.getId()), false));
            });
        }
        return result;
    }

    private List<Long> isLike(Long userId, List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Boolean> likedMap = contentInternalPort.likedMap(userId, new HashSet<>(videoIds));
        return likedMap.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }

        return Long.parseLong(userId);
    }
}

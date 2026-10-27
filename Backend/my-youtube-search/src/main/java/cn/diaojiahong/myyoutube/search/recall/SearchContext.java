package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoByTagPageQuery;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一次搜索请求的共享状态, 贯穿召回全链路:
 * 保存搜索条件与已取得的关注列表；查询向量只在向量路内部使用
 */
@Getter
@RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class SearchContext {

    private final SearchVideoPageQuery query;
    private final Long userId;
    private final String keyword;

    private final List<Long> followIds;
    private final boolean tagSearch;

    public static SearchContext of(SearchVideoPageQuery query, Long userId, List<Long> followIds) {
        String keyword = query.getKeyword() == null ? "" : query.getKeyword().trim();
        return new SearchContext(query, userId, keyword, List.copyOf(followIds), false);
    }

    public static SearchContext forTag(SearchVideoByTagPageQuery source, Long userId, List<Long> followIds) {
        var query = new SearchVideoPageQuery();
        query.setKeyword(source.getTagName());
        query.setPageSize(source.getPageSize());
        String keyword = source.getTagName() == null ? "" : source.getTagName().trim();
        return new SearchContext(query, userId, keyword, List.copyOf(followIds), true);
    }

    public boolean isSortByLatest() {
        return query.isSortByLatest();
    }

    public String getCategory() {
        return query.getCategory();
    }
}

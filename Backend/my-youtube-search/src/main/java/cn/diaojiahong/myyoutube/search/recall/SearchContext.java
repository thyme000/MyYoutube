package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoPageQuery;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一次搜索请求的共享状态, 贯穿召回全链路:
 * 保存召回起点与已取得的关注列表；查询向量只在向量路内部使用
 */
@Getter
@RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class SearchContext {

    private final SearchVideoPageQuery query;
    private final Long userId;
    private final String keyword;

    private final List<Long> followIds;

    private final long startedNanos = System.nanoTime();

    public static SearchContext of(SearchVideoPageQuery query, Long userId, List<Long> followIds) {
        String keyword = query.getKeyword() == null ? "" : query.getKeyword().trim();
        return new SearchContext(query, userId, keyword, List.copyOf(followIds));
    }

    public boolean isSortByLatest() {
        return query.isSortByLatest();
    }

    public String getCategory() {
        return query.getCategory();
    }
}

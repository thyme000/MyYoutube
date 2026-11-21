package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
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

    private final ContentSearchCursorQuery query;
    private final Long userId;
    private final String keyword;

    private final List<Long> followIds;
    private final List<Long> blockedAuthors;
    private final boolean tagSearch;

    boolean authorVisible(Long authorId) {
        return authorId == null || !blockedAuthors.contains(authorId);
    }

    public static SearchContext of(ContentSearchCursorQuery query, Long userId, List<Long> followIds, List<Long> blockedAuthors) {
        String keyword = query.getKeyword() == null ? "" : query.getKeyword().trim();
        return new SearchContext(query, userId, keyword, List.copyOf(followIds), List.copyOf(blockedAuthors), false);
    }

    public static SearchContext forTag(TagSearchCursorQuery source, Long userId, List<Long> followIds, List<Long> blockedAuthors) {
        var query = new ContentSearchCursorQuery();
        query.setKeyword(source.getTagName());
        query.setCursor(source.getCursor());
        String keyword = source.getTagName() == null ? "" : source.getTagName().trim();
        return new SearchContext(query, userId, keyword, List.copyOf(followIds), List.copyOf(blockedAuthors), true);
    }

    public boolean isSortByLatest() {
        return query.isSortByLatest();
    }

    public String getCategory() {
        return query.getCategory();
    }
}

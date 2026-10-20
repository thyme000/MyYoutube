package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoPageQuery;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/**
 * 一次搜索请求的共享状态, 贯穿召回全链路:
 * 保存请求起点与关注列表；查询向量只在向量路内部使用
 */
@Getter
@RequiredArgsConstructor
public class SearchContext {

    private final SearchVideoPageQuery query;
    private final Long userId;
    private final String keyword;

    private final long startedNanos = System.nanoTime();
    /** 向量单路返回时关注查询可能尚未完成，读取快照不等待慢路。 */
    private volatile List<Long> followIds = List.of();
    @Getter(lombok.AccessLevel.NONE)
    private FutureTask<Void> followIdsLoader;

    public static SearchContext of(SearchVideoPageQuery query, Long userId) {
        String keyword = query.getKeyword() == null ? "" : query.getKeyword().trim();
        return new SearchContext(query, userId, keyword);
    }

    public boolean isSortByLatest() {
        return query.isSortByLatest();
    }

    public String getCategory() {
        return query.getCategory();
    }

    void prepareFollowIds(Supplier<List<Long>> loader) {
        followIdsLoader = new FutureTask<>(() -> {
            followIds = List.copyOf(loader.get());
            return null;
        });
    }

    void loadFollowIds() throws InterruptedException, ExecutionException {
        // FutureTask 让多个依赖策略共用一次 RPC，包括查询失败的情况。
        followIdsLoader.run();
        followIdsLoader.get();
    }
}

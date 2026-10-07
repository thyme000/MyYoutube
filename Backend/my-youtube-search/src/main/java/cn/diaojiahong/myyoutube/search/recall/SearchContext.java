package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoPageQuery;
import cn.diaojiahong.myyoutube.search.vector.ContentVectorService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一次搜索请求的共享状态, 贯穿召回全链路:
 * prep 阶段填充的关注列表/查询向量挂在这里, 各策略免重复查询
 */
@Getter
@RequiredArgsConstructor
public class SearchContext {

    private final SearchVideoPageQuery query;
    private final Long userId;
    private final String keyword;

    /** 查询向量, null = 开关关闭/向量生成失败, 向量路自行跳过 */
    private ContentVectorService.QueryVector queryVector;
    /** 综合排序才有值(ES 打分与 isFollow 回填共用), 最新排序恒为空 */
    private List<Long> followIds;

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

    void attachQueryVector(ContentVectorService.QueryVector queryVector) {
        this.queryVector = queryVector;
    }

    void attachFollowIds(List<Long> followIds) {
        this.followIds = followIds;
    }
}

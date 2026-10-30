package cn.diaojiahong.myyoutube.search.domain.dto;

import cn.diaojiahong.myyoutube.common.page.CursorPageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "内容搜索参数；综合首批取 max(pageSize, 配置首批数量)。默认配置下请求 10 条，首批最多返回 30 条，后续最多 10 条")
public class ContentSearchCursorQuery extends CursorPageQuery {
    @Schema(description = "搜索关键词", maxLength = SearchQueryValidation.MAX_KEYWORD_LENGTH)
    private String keyword;

    // 搜索类型(长视频/短视频)
    // 视频种类
    private String category;
    // 是否要按最新发布排序
    private boolean sortByLatest;

    @Override
    public void validate() {
        super.validate();
        SearchQueryValidation.validateKeyword(keyword);
    }
}

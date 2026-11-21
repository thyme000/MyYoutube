package cn.diaojiahong.myyoutube.search.domain.dto;

import cn.diaojiahong.myyoutube.common.page.CursorPageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "内容搜索参数。综合首批条数和后续每页条数由服务端配置决定，调用方只回传游标")
public class ContentSearchCursorQuery extends CursorPageQuery {
    @Schema(description = "搜索关键词", maxLength = SearchQueryValidation.MAX_KEYWORD_LENGTH)
    private String keyword;

    // 搜索类型(长视频/短视频)
    private String category;
    private boolean sortByLatest;

    public void validate() {
        SearchQueryValidation.validateKeyword(keyword);
    }
}

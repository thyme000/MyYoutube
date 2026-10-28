package cn.diaojiahong.myyoutube.search.domain.dto;

import cn.diaojiahong.myyoutube.common.page.CursorPageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@EqualsAndHashCode(callSuper = true)
public class TagSearchCursorQuery extends CursorPageQuery {
    @Schema(description = "标签搜索词", maxLength = SearchQueryValidation.MAX_KEYWORD_LENGTH)
    private String tagName;
    @Override
    public void validate() {
        super.validate();
        SearchQueryValidation.validateKeyword(tagName);
    }
}

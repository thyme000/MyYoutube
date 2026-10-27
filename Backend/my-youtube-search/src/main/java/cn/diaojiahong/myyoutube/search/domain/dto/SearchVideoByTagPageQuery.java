package cn.diaojiahong.myyoutube.search.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import lombok.Data;

@Data
public class SearchVideoByTagPageQuery {
    private String tagName;
    private int pageSize = 10;
    private String cursor;

    public void validate() { PageQuery.validate(1, pageSize); }
}

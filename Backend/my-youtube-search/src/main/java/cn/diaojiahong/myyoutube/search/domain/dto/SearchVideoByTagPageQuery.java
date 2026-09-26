package cn.diaojiahong.myyoutube.search.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import lombok.Data;

@Data
public class SearchVideoByTagPageQuery extends PageQuery {
    private String tagName;
}

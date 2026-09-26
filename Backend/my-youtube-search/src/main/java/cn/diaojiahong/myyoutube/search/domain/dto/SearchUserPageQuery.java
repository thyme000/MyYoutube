package cn.diaojiahong.myyoutube.search.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import lombok.Data;

@Data
public class SearchUserPageQuery extends PageQuery {
    // 关键字
    private String keyword;
}

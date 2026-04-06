package cn.diaojiahong.myyoutube.search.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import lombok.Data;

@Data
public class SearchVideoPageQuery extends PageQuery {
    // 搜索关键词
    private String keyword;
    // 搜索类型(长视频/短视频)
    private String videoType;
    // 视频种类
    private String category;
    // 是否要按最新发布排序
    private boolean sortByLatest;
}

package cn.diaojiahong.myyoutube.search.recall;

import java.util.List;

/**
 * 融合器: 把各路召回(各自内部有序)融合成统一有序 id 列表, 排序语义收口在这里
 */
public interface RecallFuser {

    List<String> fuse(SearchContext ctx, List<List<String>> recalls);
}

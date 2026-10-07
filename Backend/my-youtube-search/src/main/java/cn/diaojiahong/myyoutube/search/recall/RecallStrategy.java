package cn.diaojiahong.myyoutube.search.recall;

import java.util.List;

/**
 * 一条召回路: 只负责"找出相关的 id", 排序语义归融合器
 * 加新路 = 新增一个 @Component 实现, Pipeline 自动编排
 */
public interface RecallStrategy {

    /** 开关未开/前置条件不满足时跳过该路 */
    boolean shouldRun(SearchContext ctx);

    /** 按相关性降序返回召回 id, 原始分不出策略边界 */
    List<String> recall(SearchContext ctx);
}

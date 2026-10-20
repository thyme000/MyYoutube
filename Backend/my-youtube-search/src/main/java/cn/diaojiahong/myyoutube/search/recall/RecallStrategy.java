package cn.diaojiahong.myyoutube.search.recall;

import java.util.List;

/**
 * 一条召回路: 只负责"找出相关的 id", 排序语义归融合器
 * 加新路 = 新增一个 @Component 实现, Pipeline 自动编排
 */
public interface RecallStrategy {

    /** 开关未开/前置条件不满足时跳过该路 */
    boolean shouldRun(SearchContext ctx);

    default String executorName() { return "recallExecutor"; }

    default boolean needsFollowIds() { return false; }

    /** 按相关性降序返回 id；deadlineNanos 是包含排队与依赖查询的单调时钟硬截止时间。 */
    List<String> recall(SearchContext ctx, long deadlineNanos) throws Exception;
}

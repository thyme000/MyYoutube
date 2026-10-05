package cn.diaojiahong.myyoutube.common.counter;

/**
 * 一种计数的落库目标。各模块按自己持有的表实现并注册成 Bean，
 * {@link CounterFlushService} 只认接口，不关心计数落在哪张表。
 */
public interface CounterTarget {

    CounterType type();

    /**
     * 把增量应用到 DB。delta 为负时必须自行护底不得为负 ——
     * 计数的正确性来源是点赞/关注关系表的状态机，不是计数器自己的值。
     */
    void applyDelta(long id, long delta);

    /**
     * 权威值 COUNT(*)。返回 null 表示该 id 没有可信来源，对账跳过。
     * 只有 type().reconcile() 为 true 的类型会被调用。
     */
    Long authoritativeValue(long id);

    /** 无条件覆盖为权威值。不是累加 —— 所以任意方向的误差都能在下一轮被拉回真值 */
    void overwrite(long id, long value);

    /**
     * 落库 ack 之后的旁路同步(如把增量推给 ES 文档)。骨架保证只在 ack 成功后调用 ——
     * 放在 ack 前, 该同步一失败 DB 增量就会被下轮重放双计。抛错由骨架吞掉记日志, 不影响落库。
     */
    default void afterAck(long id, long delta) {
    }
}

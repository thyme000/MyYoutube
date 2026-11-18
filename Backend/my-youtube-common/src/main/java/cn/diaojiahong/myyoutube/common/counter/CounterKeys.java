package cn.diaojiahong.myyoutube.common.counter;

/**
 * 计数缓冲的 key 拼装。写侧与落库侧必须用同一份拼装规则，否则跨模块的计数会各写各的。
 */
public final class CounterKeys {

    private static final String PENDING_PREFIX = "pending:";
    private static final String INFLIGHT_PREFIX = "inflight:";
    private static final String DIRTY_PREFIX = "dirty:";

    private CounterKeys() {
    }

    /** 累积增量 */
    public static String pending(CounterType type, long id) {
        return pendingPrefix(type) + id;
    }

    /** SCAN 用前缀 */
    public static String pendingPrefix(CounterType type) {
        return PENDING_PREFIX + type.code() + ":";
    }

    /** 已认领未确认 */
    public static String inflight(CounterType type, long id) {
        return inflightPrefix(type) + id;
    }

    /** SCAN 用前缀；回补必须扫全量 inflight，不能只扫 dirty 集合 */
    public static String inflightPrefix(CounterType type) {
        return INFLIGHT_PREFIX + type.code() + ":";
    }

    /** 待对账 id 集合 */
    public static String dirty(CounterType type) {
        return DIRTY_PREFIX + type.code();
    }
}

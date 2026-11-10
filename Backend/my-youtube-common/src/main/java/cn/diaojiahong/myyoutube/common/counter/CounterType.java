package cn.diaojiahong.myyoutube.common.counter;

/**
 * 计数缓冲类型。code 参与 Redis key 拼装；落库目标由各模块注册的 {@link CounterTarget} 决定，
 * 同一个 type 的写侧与落库侧可以分处不同模块（共享 Redis 实例即可）。
 */
public enum CounterType {

    CONTENT_LIKE("content:like", true),
    COMMENT_LIKE("comment:like", true),
    USER_FANS("user:fans", true),
    USER_ATTENTION("user:attention", true),
    /** 收藏数。权威值是 content_collect 行数 */
    CONTENT_COLLECT("content:collect", true),
    /**
     * 结构上不会漂移故不做对账。枚举保留供重算工具定位权威值(content_share 行数)。
     */
    CONTENT_SHARE("content:share", false);

    private final String code;
    private final boolean reconcile;

    CounterType(String code, boolean reconcile) {
        this.code = code;
        this.reconcile = reconcile;
    }

    public String code() {
        return code;
    }

    public boolean reconcile() {
        return reconcile;
    }
}

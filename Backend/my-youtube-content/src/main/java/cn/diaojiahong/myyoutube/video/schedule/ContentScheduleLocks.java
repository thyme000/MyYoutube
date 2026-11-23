package cn.diaojiahong.myyoutube.video.schedule;

/** 内容服务调度任务的整轮锁名称。 */
public final class ContentScheduleLocks {
    private ContentScheduleLocks() {
    }

    static final String COUNTER_FLUSH = "schedule:lock:content:counter:flush";
    static final String COUNTER_RECONCILE = "schedule:lock:content:counter:reconcile";
    static final String ES_COUNT_SYNC = "schedule:lock:content:es-count-sync";
    public static final String CF_REBUILD = "schedule:lock:content:cf-rebuild";
}

package cn.diaojiahong.myyoutube.video.schedule;

/** 视频域调度任务的整轮锁名称。 */
public final class VideoScheduleLocks {
    private VideoScheduleLocks() {
    }

    static final String COUNTER_FLUSH = "schedule:lock:video:counter:flush";
    static final String COUNTER_RECONCILE = "schedule:lock:video:counter:reconcile";
    static final String PLAY_COUNT_FLUSH = "schedule:lock:video:play-count";
    static final String ES_COUNT_SYNC = "schedule:lock:video:es-count-sync";
    public static final String CF_REBUILD = "schedule:lock:video:cf-rebuild";
}

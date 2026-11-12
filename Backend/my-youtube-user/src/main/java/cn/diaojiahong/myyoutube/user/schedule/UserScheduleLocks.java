package cn.diaojiahong.myyoutube.user.schedule;

/** 用户域调度任务的整轮锁名称。 */
final class UserScheduleLocks {
    private UserScheduleLocks() {
    }

    static final String COUNTER_FLUSH = "schedule:lock:user:counter:flush";
    static final String COUNTER_RECONCILE = "schedule:lock:user:counter:reconcile";
}

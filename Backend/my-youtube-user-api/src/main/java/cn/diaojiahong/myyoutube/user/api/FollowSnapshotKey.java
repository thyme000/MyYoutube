package cn.diaojiahong.myyoutube.user.api;

/**
 * 关注快照的 Redis key。user 写入，video 读取，两边共用这一份拼装。
 */
public final class FollowSnapshotKey {

    private FollowSnapshotKey() {
    }

    public static String key(long userId) {
        return "follow:ids:" + userId;
    }
}

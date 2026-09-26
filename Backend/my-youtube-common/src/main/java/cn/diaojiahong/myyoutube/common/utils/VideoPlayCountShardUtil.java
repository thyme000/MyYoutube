package cn.diaojiahong.myyoutube.common.utils;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;

/**
 * 播放量缓冲写入 Redis 时的分片路由：同一 videoId 永远落在同一分片 Hash，便于定时任务按片扫描落库。
 */
public final class VideoPlayCountShardUtil {

    private VideoPlayCountShardUtil() {}

    /**
     * 根据视频 id 计算分片下标 [0, {@link RedisConstants#VIDEO_PLAY_COUNT_SHARD_COUNT})
     */
    public static int shardIndex(Object videoId) {
        String s = String.valueOf(videoId);
        return Math.floorMod(s.hashCode(), RedisConstants.VIDEO_PLAY_COUNT_SHARD_COUNT);
    }

    /**
     * 该视频播放量增量应写入的 Redis Hash key
     */
    public static String redisHashKey(Object videoId) {
        return RedisConstants.videoPlayCountShardKey(shardIndex(videoId));
    }
}

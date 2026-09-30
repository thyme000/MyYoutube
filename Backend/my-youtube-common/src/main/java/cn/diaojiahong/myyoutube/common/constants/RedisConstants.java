package cn.diaojiahong.myyoutube.common.constants;

public class RedisConstants {

    // 用户登录token凭证
    public static final String USER_TOKEN = "user:token:";
    public static final Long USER_TOKEN_EXPIRE = 24L;

    // 视频播放量
    public static final String VIDEO_PLAY_COUNT = "video:play:count:";
    public static final Long VIDEO_PLAY_COUNT_EXPIRE = 3L;

    /** 分片数量（2^8），按 videoId 稳定路由，减轻单 Hash 过大 */
    public static final int VIDEO_PLAY_COUNT_SHARD_COUNT = 256;

    /**
     * 分片后的 Redis Hash key：video:play:count:0 .. video:play:count:{SHARD_COUNT-1}
     */
    public static String videoPlayCountShardKey(int shard) {
        if (shard < 0 || shard >= VIDEO_PLAY_COUNT_SHARD_COUNT) {
            throw new IllegalArgumentException("shard out of range: " + shard);
        }
        return VIDEO_PLAY_COUNT + shard;
    }

    // 用户看过的视频id
    public static final String USER_VIEWED = "user:viewed:";
    // 30天后过期
    public static final Long USER_VIEWED_EXPIRE = 30L;
    // 用户踩过的视频id
    public static final String USER_DISLIKE = "user:dislike:";
    // 60天后过期
    public static final Long USER_DISLIKE_EXPIRE = 60L;


    // 用户看过的长视频时长记录 ->  {userid} : videoId , 当前时间戳
    public static final String USER_PLAYBACK_RESUME_TIMESTAMP = "user:playback:resume:timestamp:";
    // 30天后过期
    public static final Long USER_PLAYBACK_RESUME_EXPIRE = 30L;
    // 用户看过的视频时长记录 ->  {userid} : videoId , 当前时间戳
    public static final String USER_PLAYBACK_RESUME = "user:playback:resume:";


    // 用户观看的短视频记录
    public static final String USER_SHORTS_PLAY = "user:shorts:play:";
    // 30天后过期
    public static final Long USER_SHORTS_PLAY_EXPIRE = 30L;

    // 用户获赞量 {userid} string结构 videoId : likeCount
    public static final String USER_LIKE_COUNT = "user:like:count:";

    /**
     * 定时任务：按主键游标轮询，将 video 表中的播放量/点赞量同步到 ES（仅存 lastId，重启可续跑）
     */
    public static final String SCHEDULE_ES_VIDEO_COUNT_SYNC_CURSOR = "schedule:es:video:count:sync:cursor";

    /**
     * 用户赞过的评论
     */
    public static final String USER_LIKE_COMMENT = "user:like:comment:";
    // 10天后过期
    public static final Long USER_LIKE_COMMENT_EXPIRE = 10L;

    /**
     * 热搜榜(top7)
     */
    public static final String HOT_SEARCH = "hot:search";
    // 7天后过期
    public static final Long HOT_SEARCH_EXPIRE = 7L;
    // 用户搜过的关键词,防止重复搜索
    public static final String USER_SEARCH_KEYWORD = "user:search:keyword:";
    // 30分钟后过期
    public static final Long USER_SEARCH_KEYWORD_EXPIRE = 30L;

    /**
     * 用户会话记录(用户记录非互关用户) -> 非互关关系每天只能给对方发10条消息
     */
    public static final String USER_CONVERSATION = "user:conversation:";
    // 1天后过期
    public static final Long USER_CONVERSATION_EXPIRE = 1L;

    /** 多图合成视频任务 merge:pic:job:{jobId} */
    public static final String MERGE_PICTURE_JOB = "merge:pic:job:";
    /** 任务状态在 Redis 中的保留时间（分钟） */
    public static final long MERGE_PICTURE_JOB_EXPIRE_MINUTES = 60L;

    // ==================== 计数落库缓冲 ====================
    /** 累积增量 pending:{type}:{id}; 每轮落库后 DEL, 故不设 TTL —— 设了反而会在任务停摆时丢增量 */
    public static final String COUNTER_PENDING = "pending:";
    /** 已认领未确认 inflight:{type}:{id} */
    public static final String COUNTER_INFLIGHT = "inflight:";
    /** 最近被写过的 id 集合 dirty:{type}, 只用于对账自愈 */
    public static final String COUNTER_DIRTY = "dirty:";
    /** inflight 租期(秒): 落库任务停摆超过该时长即视为崩溃, 下轮由认领脚本回补退回 pending */
    public static final long COUNTER_INFLIGHT_TTL_SECONDS = 600L;
    /** dirty 集合保留时长(秒), 取对账周期(1h)的 2 倍 */
    public static final long COUNTER_DIRTY_TTL_SECONDS = 7200L;
    /** 单轮落库处理 id 数上限, 给单轮耗时设上界, 保证短于锁租期 */
    public static final int COUNTER_MAX_KEYS_PER_ROUND = 1000;
    /** 整轮锁租期(秒): 显式设租期而不依赖看门狗 —— 长 STW 时看门狗线程同样会停 */
    public static final long SCHEDULE_LOCK_LEASE_SECONDS = 300L;
}

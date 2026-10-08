package cn.diaojiahong.myyoutube.common.constants;

import cn.diaojiahong.myyoutube.security.AuthConstants;

public class RedisConstants {

    // refresh token: {uuid} -> "{userId}:{签发毫秒}", 每次续签轮换
    public static final String USER_REFRESH_TOKEN = "user:refresh:";
    public static final Long USER_REFRESH_TOKEN_EXPIRE = 7 * 24L;
    // access token 作废时间戳: {userId} -> 毫秒, 签发早于它的 access/refresh 全部拒绝
    public static final String USER_TOKEN_VER = AuthConstants.TOKEN_VER_KEY_PREFIX;

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

    // 标签被内容引用的次数 ZSET, member=tagId; 打标链路维护, 热门标签榜数据源
    public static final String CONTENT_TAG_REF = "content:tag:ref";

    // 标签词库池, 按类目分 ZSET(member=slug, score=引用数热度), 通用池后缀 common; 打标链路维护
    public static final String TAG_POOL = "content:tag:pool:";
    // 词库元数据 Hash: slug → id|name|categoryCode
    public static final String TAG_POOL_META = "content:tag:pool:meta";


    // 用户看过的长视频时长记录 ->  {userid} : videoId , 当前时间戳
    public static final String USER_PLAYBACK_RESUME_TIMESTAMP = "user:playback:resume:timestamp:";
    // 30天后过期
    public static final Long USER_PLAYBACK_RESUME_EXPIRE = 30L;
    // 用户看过的视频时长记录 ->  {userid} : videoId , 当前时间戳
    public static final String USER_PLAYBACK_RESUME = "user:playback:resume:";


    // 用户观看的短视频记录
    // 30天后过期
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


    /** 多实例推送扇出: 聊天 WS 通道(承载聊天事件报文) */
    public static final String PUSH_CHANNEL_WS = "push:channel:ws";
    /** 多实例推送扇出: SSE 门铃通道(只承载"有新通知"轻量信号) */
    public static final String PUSH_CHANNEL_SSE = "push:channel:sse";

    /** 长连接连接票: value=userId, 短时效一次性, 由网关 GETDEL 原子消费 */
    public static final String PUSH_CONNECTION_TICKET = AuthConstants.PUSH_TICKET_KEY_PREFIX;

    /** 动态成片 LRU: zset member=taskKey, score=最近访问毫秒 */
    public static final String TRANSCODE_LRU = "transcode:lru";

    // ==================== 协同过滤相似度缓存 ====================
    /** 物品相似度 Top-K: recommend:sim:{算法}:{内容ID} zset member=邻居内容ID score=相似度 */
    public static final String RECOMMEND_SIM = "recommend:sim:";
    /** 相似度缓存 TTL(天): 每日全量重算兜底, TTL 略大于重算周期 */
    public static final Long RECOMMEND_SIM_EXPIRE = 8L;
    /** CF 增量任务行为流水水位(已处理到的最大行为 id) */
    public static final String SCHEDULE_CF_WATERMARK = "schedule:cf:behavior:watermark";

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

    /** 方法级幂等占位 idempotent:{业务key}, value=RUNNING/DONE/结果JSON */
    public static final String IDEMPOTENT = "idempotent:";

    /** 内部契约的用户摘要缓存: user:summary:{userId} hash, 逐字段存; 资料变更时 DEL */
    public static final String USER_SUMMARY = "user:summary:";

    /** 评论有序名单: comment:list:{tab}:{contentId} string, 值=「排序值.评论id」按榜序逗号相连 */
    public static final String COMMENT_LIST = "comment:list:";
    /** 名单重建单飞锁: comment:list:lock:{tab}:{contentId} */
    public static final String COMMENT_LIST_LOCK = "comment:list:lock:";
}
